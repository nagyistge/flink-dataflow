/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.dataartisans.flink.dataflow.examples.streaming;

import com.dataartisans.flink.dataflow.FlinkPipelineRunner;
import com.dataartisans.flink.dataflow.translation.wrappers.streaming.io.UnboundedSocketSource;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.Read;
import com.google.cloud.dataflow.sdk.io.TextIO;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.join.CoGbkResult;
import com.google.cloud.dataflow.sdk.transforms.join.CoGroupByKey;
import com.google.cloud.dataflow.sdk.transforms.join.KeyedPCollectionTuple;
import com.google.cloud.dataflow.sdk.transforms.windowing.*;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PBegin;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.TupleTag;
import org.joda.time.Duration;

/**
 * To run the example, first open two sockets on two terminals by executing the commands:
 * <li>
 *     <li>
 *         <code>nc -lk 9999</code>, and
 *     </li>
 *     <li>
 *         <code>nc -lk 9998</code>
 *     </li>
 * </li>
 * and then launch the example. Now whatever you type in the terminal is going to be
 * the input to the program.
 * */
public class JoinExamples {

	static PCollection<String> joinEvents(PCollection<String> streamA,
										  PCollection<String> streamB) throws Exception {

		final TupleTag<String> firstInfoTag = new TupleTag<String>();
		final TupleTag<String> secondInfoTag = new TupleTag<String>();

		// transform both input collections to tuple collections, where the keys are country
		// codes in both cases.
		PCollection<KV<String, String>> firstInfo = streamA.apply(
				ParDo.of(new ExtractEventDataFn()));
		PCollection<KV<String, String>> secondInfo = streamB.apply(
				ParDo.of(new ExtractEventDataFn()));

		// country code 'key' -> CGBKR (<event info>, <country name>)
		PCollection<KV<String, CoGbkResult>> kvpCollection = KeyedPCollectionTuple
				.of(firstInfoTag, firstInfo)
				.and(secondInfoTag, secondInfo)
				.apply(CoGroupByKey.<String>create());

		// Process the CoGbkResult elements generated by the CoGroupByKey transform.
		// country code 'key' -> string of <event info>, <country name>
		PCollection<KV<String, String>> finalResultCollection =
				kvpCollection.apply(ParDo.named("Process").of(
						new DoFn<KV<String, CoGbkResult>, KV<String, String>>() {
							private static final long serialVersionUID = 0;

							@Override
							public void processElement(ProcessContext c) {
								KV<String, CoGbkResult> e = c.element();
								String key = e.getKey();

								String defaultA = "NO_VALUE";

								// the following getOnly is a bit tricky because it expects to have
								// EXACTLY ONE value in the corresponding stream and for the corresponding key.

								String lineA = e.getValue().getOnly(firstInfoTag, defaultA);
								for (String lineB : c.element().getValue().getAll(secondInfoTag)) {
									// Generate a string that combines information from both collection values
									c.output(KV.of(key, "Value A: " + lineA + " - Value B: " + lineB));
								}
							}
						}));

		return finalResultCollection
				.apply(ParDo.named("Format").of(new DoFn<KV<String, String>, String>() {
					private static final long serialVersionUID = 0;

					@Override
					public void processElement(ProcessContext c) {
						String result = c.element().getKey() + " -> " + c.element().getValue();
						System.out.println(result);
						c.output(result);
					}
				}));
	}

	static class ExtractEventDataFn extends DoFn<String, KV<String, String>> {
		private static final long serialVersionUID = 0;

		@Override
		public void processElement(ProcessContext c) {
			String line = c.element().toLowerCase();
			String key = line.split("\\s")[0];
			c.output(KV.of(key, line));
		}
	}

	private static interface Options extends WindowedWordCount.StreamingWordCountOptions {

	}

	public static void main(String[] args) throws Exception {
		Options options = PipelineOptionsFactory.fromArgs(args).withValidation().as(Options.class);

		// make it a streaming example.
		options.setStreaming(true);
		options.setRunner(FlinkPipelineRunner.class);

		PTransform<? super PBegin, PCollection<String>> readSourceA =
				Read.from(new UnboundedSocketSource<>("localhost", 9999, '\n', 3)).named("FirstStream");
		PTransform<? super PBegin, PCollection<String>> readSourceB =
				Read.from(new UnboundedSocketSource<>("localhost", 9998, '\n', 3)).named("SecondStream");

		WindowFn<Object, ?> windowFn = FixedWindows.of(Duration.standardSeconds(options.getWindowSize()));

		Pipeline p = Pipeline.create(options);

		// the following two 'applys' create multiple inputs to our pipeline, one for each
		// of our two input sources.
		PCollection<String> streamA = p.apply(readSourceA)
				.apply(Window.<String>into(windowFn)
						.triggering(AfterWatermark.pastEndOfWindow()).withAllowedLateness(Duration.ZERO)
						.discardingFiredPanes());
		PCollection<String> streamB = p.apply(readSourceB)
				.apply(Window.<String>into(windowFn)
						.triggering(AfterWatermark.pastEndOfWindow()).withAllowedLateness(Duration.ZERO)
						.discardingFiredPanes());

		PCollection<String> formattedResults = joinEvents(streamA, streamB);
		formattedResults.apply(TextIO.Write.to("./outputJoin.txt"));
		p.run();
	}

}
