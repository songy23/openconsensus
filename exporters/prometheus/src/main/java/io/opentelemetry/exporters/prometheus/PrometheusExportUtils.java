/*
 * Copyright 2019, OpenTelemetry Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.opentelemetry.exporters.prometheus;

import static io.prometheus.client.Collector.doubleToGoString;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import io.opentelemetry.proto.metrics.v1.HistogramValue;
import io.opentelemetry.proto.metrics.v1.HistogramValue.BucketOptions;
import io.opentelemetry.proto.metrics.v1.LabelValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.MetricDescriptor;
import io.opentelemetry.proto.metrics.v1.Point;
import io.opentelemetry.proto.metrics.v1.SummaryValue;
import io.opentelemetry.proto.metrics.v1.SummaryValue.Snapshot.ValueAtPercentile;
import io.opentelemetry.proto.metrics.v1.TimeSeries;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.MetricFamilySamples;
import io.prometheus.client.Collector.MetricFamilySamples.Sample;
import io.prometheus.client.Collector.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class PrometheusExportUtils {

  @VisibleForTesting static final String SAMPLE_SUFFIX_BUCKET = "_bucket";
  @VisibleForTesting static final String SAMPLE_SUFFIX_COUNT = "_count";
  @VisibleForTesting static final String SAMPLE_SUFFIX_SUM = "_sum";
  @VisibleForTesting static final String LABEL_NAME_BUCKET_BOUND = "le";
  @VisibleForTesting static final String LABEL_NAME_QUANTILE = "quantile";

  // Converts a Metric to a Prometheus MetricFamilySamples.
  static MetricFamilySamples createMetricFamilySamples(Metric metric, String namespace) {
    MetricDescriptor metricDescriptor = metric.getMetricDescriptor();
    String name = getNamespacedName(metricDescriptor.getName(), namespace);
    Type type = getType(metricDescriptor.getType());
    List<String> labelNames = convertToLabelNames(metricDescriptor.getLabelKeysList());
    List<Sample> samples = Lists.newArrayList();

    for (TimeSeries timeSeries : metric.getTimeseriesList()) {
      for (Point point : timeSeries.getPointsList()) {
        samples.addAll(
            getSamples(
                name,
                labelNames,
                timeSeries.getLabelValuesList(),
                point,
                metricDescriptor.getType()));
      }
    }
    return new MetricFamilySamples(name, type, metricDescriptor.getDescription(), samples);
  }

  // Converts a MetricDescriptor to a Prometheus MetricFamilySamples.
  // Used only for Prometheus metric registry, should not contain any actual samples.
  static MetricFamilySamples createDescribableMetricFamilySamples(
      MetricDescriptor metricDescriptor, String namespace) {
    String name = getNamespacedName(metricDescriptor.getName(), namespace);
    Type type = getType(metricDescriptor.getType());
    List<String> labelNames = convertToLabelNames(metricDescriptor.getLabelKeysList());

    if (containsDisallowedLeLabelForHistogram(labelNames, type)) {
      throw new IllegalStateException(
          "Prometheus Histogram cannot have a label named 'le', "
              + "because it is a reserved label for bucket boundaries. "
              + "Please remove this key from your view.");
    }

    if (containsDisallowedQuantileLabelForSummary(labelNames, type)) {
      throw new IllegalStateException(
          "Prometheus Summary cannot have a label named 'quantile', "
              + "because it is a reserved label. Please remove this key from your view.");
    }

    return new MetricFamilySamples(
        name, type, metricDescriptor.getDescription(), Collections.<Sample>emptyList());
  }

  private static String getNamespacedName(String metricName, String namespace) {
    if (!namespace.isEmpty()) {
      if (!namespace.endsWith("/") && !namespace.endsWith("_")) {
        namespace += '_';
      }
      metricName = namespace + metricName;
    }
    return Collector.sanitizeMetricName(metricName);
  }

  @VisibleForTesting
  static Type getType(MetricDescriptor.Type type) {
    switch (type) {
      case COUNTER_INT64:
      case COUNTER_DOUBLE:
        return Type.COUNTER;
      case GAUGE_DOUBLE:
      case GAUGE_INT64:
        return Type.GAUGE;
      case CUMULATIVE_HISTOGRAM:
      case GAUGE_HISTOGRAM:
        return Type.HISTOGRAM;
      case SUMMARY:
        return Type.SUMMARY;
      case UNRECOGNIZED:
      case UNSPECIFIED:
        return Type.UNTYPED;
    }
    return Type.UNTYPED;
  }

  // Converts a point value in Metric to a list of Prometheus Samples.
  @VisibleForTesting
  static List<Sample> getSamples(
      String name,
      List<String> labelNames,
      List<LabelValue> labelValuesList,
      Point point,
      MetricDescriptor.Type type) {
    Preconditions.checkArgument(
        labelNames.size() == labelValuesList.size(), "Keys and Values don't have same size.");
    List<String> labelValues = new ArrayList<String>(labelValuesList.size());
    for (LabelValue labelValue : labelValuesList) {
      String val = labelValue.getHasValue() ? labelValue.getValue() : "";
      labelValues.add(val);
    }

    switch (type) {
      case COUNTER_DOUBLE:
      case GAUGE_DOUBLE:
        return Collections.singletonList(
            new Sample(name, labelNames, labelValues, point.getDoubleValue()));
      case COUNTER_INT64:
      case GAUGE_INT64:
        return Collections.singletonList(
            new Sample(name, labelNames, labelValues, point.getInt64Value()));
      case CUMULATIVE_HISTOGRAM:
      case GAUGE_HISTOGRAM:
        return histogramToSamples(name, labelNames, labelValues, point.getHistogramValue());
      case SUMMARY:
        return summaryToSamples(name, labelNames, labelValues, point.getSummaryValue());
      case UNRECOGNIZED:
      case UNSPECIFIED:
        return Collections.emptyList();
    }
    return Collections.emptyList();
  }

  private static List<Sample> histogramToSamples(
      final String name,
      final List<String> labelNames,
      List<String> labelValues,
      HistogramValue histogram) {
    BucketOptions bucketOptions = histogram.getBucketOptions();
    List<Double> boundaries = Collections.emptyList();
    if (histogram.hasBucketOptions() && bucketOptions.hasExplicit()) {
      boundaries = bucketOptions.getExplicit().getBoundsList();
    }

    List<String> labelNamesWithLe = new ArrayList<String>(labelNames);
    labelNamesWithLe.add(LABEL_NAME_BUCKET_BOUND);
    long cumulativeCount = 0;
    List<Sample> samples = new ArrayList<Sample>();
    for (int i = 0; i < histogram.getBucketsList().size(); i++) {
      List<String> labelValuesWithLe = new ArrayList<String>(labelValues);
      // The label value of "le" is the upper inclusive bound.
      // For the last bucket, it should be "+Inf".
      String bucketBoundary =
          doubleToGoString(i < boundaries.size() ? boundaries.get(i) : Double.POSITIVE_INFINITY);
      labelValuesWithLe.add(bucketBoundary);
      cumulativeCount += histogram.getBuckets(i).getCount();
      samples.add(
          new MetricFamilySamples.Sample(
              name + SAMPLE_SUFFIX_BUCKET, labelNamesWithLe, labelValuesWithLe, cumulativeCount));
    }

    samples.add(
        new MetricFamilySamples.Sample(
            name + SAMPLE_SUFFIX_COUNT, labelNames, labelValues, histogram.getCount()));
    samples.add(
        new MetricFamilySamples.Sample(
            name + SAMPLE_SUFFIX_SUM, labelNames, labelValues, histogram.getSum()));
    return samples;
  }

  private static List<Sample> summaryToSamples(
      final String name,
      final List<String> labelNames,
      List<String> labelValues,
      SummaryValue summaryValue) {
    List<Sample> samples = new ArrayList<Sample>();
    if (summaryValue.hasCount()) {
      samples.add(
          new MetricFamilySamples.Sample(
              name + SAMPLE_SUFFIX_COUNT,
              labelNames,
              labelValues,
              summaryValue.getCount().getValue()));
    }
    if (summaryValue.hasSum()) {
      samples.add(
          new MetricFamilySamples.Sample(
              name + SAMPLE_SUFFIX_SUM, labelNames, labelValues, summaryValue.getSum().getValue()));
    }
    if (summaryValue.hasSnapshot()) {
      List<ValueAtPercentile> valueAtPercentiles =
          summaryValue.getSnapshot().getPercentileValuesList();
      List<String> labelNamesWithQuantile = new ArrayList<String>(labelNames);
      labelNamesWithQuantile.add(LABEL_NAME_QUANTILE);
      for (ValueAtPercentile valueAtPercentile : valueAtPercentiles) {
        List<String> labelValuesWithQuantile = new ArrayList<String>(labelValues);
        labelValuesWithQuantile.add(doubleToGoString(valueAtPercentile.getPercentile() / 100));
        samples.add(
            new MetricFamilySamples.Sample(
                name,
                labelNamesWithQuantile,
                labelValuesWithQuantile,
                valueAtPercentile.getValue()));
      }
    }
    return samples;
  }

  // Converts the list of label keys to a list of string label names. Also sanitizes the label keys.
  @VisibleForTesting
  static List<String> convertToLabelNames(List<String> labelKeys) {
    final List<String> labelNames = new ArrayList<String>(labelKeys.size());
    for (String labelKey : labelKeys) {
      labelNames.add(Collector.sanitizeMetricName(labelKey));
    }
    return labelNames;
  }

  // Returns true if there is an "le" label name in histogram label names, returns false otherwise.
  // Similar check to
  // https://github.com/prometheus/client_java/blob/af39ca948ca446757f14d8da618a72d18a46ef3d/simpleclient/src/main/java/io/prometheus/client/Histogram.java#L88
  static boolean containsDisallowedLeLabelForHistogram(List<String> labelNames, Type type) {
    if (!Type.HISTOGRAM.equals(type)) {
      return false;
    }
    for (String label : labelNames) {
      if (LABEL_NAME_BUCKET_BOUND.equals(label)) {
        return true;
      }
    }
    return false;
  }

  // Returns true if there is an "quantile" label name in summary label names, returns false
  // otherwise. Similar check to
  // https://github.com/prometheus/client_java/blob/af39ca948ca446757f14d8da618a72d18a46ef3d/simpleclient/src/main/java/io/prometheus/client/Summary.java#L132
  static boolean containsDisallowedQuantileLabelForSummary(List<String> labelNames, Type type) {
    if (!Type.SUMMARY.equals(type)) {
      return false;
    }
    for (String label : labelNames) {
      if (LABEL_NAME_QUANTILE.equals(label)) {
        return true;
      }
    }
    return false;
  }

  private PrometheusExportUtils() {}
}
