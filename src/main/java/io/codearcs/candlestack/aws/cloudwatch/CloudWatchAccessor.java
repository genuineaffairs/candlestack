package io.codearcs.candlestack.aws.cloudwatch;

import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.amazonaws.services.cloudwatch.AmazonCloudWatch;
import com.amazonaws.services.cloudwatch.AmazonCloudWatchClientBuilder;
import com.amazonaws.services.cloudwatch.model.Datapoint;
import com.amazonaws.services.cloudwatch.model.Dimension;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsRequest;
import com.amazonaws.services.cloudwatch.model.GetMetricStatisticsResult;

import io.codearcs.candlestack.CandlestackException;
import io.codearcs.candlestack.MetricsReaderWriter;
import io.codearcs.candlestack.aws.CandlestackAWSException;
import io.codearcs.candlestack.aws.GlobalAWSProperties;
import io.codearcs.candlestack.aws.rds.RDSCloudWatchMetric;


public class CloudWatchAccessor {

	private static final int DETAILED_REQUEST_PERIOD = 60,
			NON_DETAILED_REQUEST_PERIOD = 300,
			MAX_DATA_POINTS = 1_440;


	private static CloudWatchAccessor instance = null;


	private AmazonCloudWatch cloudWatchClient;

	private MetricsReaderWriter metricsReaderWriter;

	private Map<String, Date> lastDatapointDateMap;

	private boolean detailedMonitoringEnabled;

	private int requestPeriod;

	private long maxTimeDifference;


	private CloudWatchAccessor() throws CandlestackException {

		metricsReaderWriter = MetricsReaderWriter.getInstance();

		detailedMonitoringEnabled = GlobalAWSProperties.isCloudWatchDetailedMonitoringEnabled();

		requestPeriod = detailedMonitoringEnabled ? DETAILED_REQUEST_PERIOD : NON_DETAILED_REQUEST_PERIOD;

		maxTimeDifference = 1000 * requestPeriod * MAX_DATA_POINTS;

		cloudWatchClient = AmazonCloudWatchClientBuilder.standard().withRegion( GlobalAWSProperties.getRegion() ).build();

		lastDatapointDateMap = new HashMap<>();

	}


	public synchronized static CloudWatchAccessor getInstance() throws CandlestackException {
		if ( instance == null ) {
			instance = new CloudWatchAccessor();
		}

		return instance;
	}


	public void lookupAndSaveMetricData( CloudWatchMetric metric, String dimensionValue, String type ) throws CandlestackAWSException, CandlestackException {

		String datapointDateMapKey = getDatapointDateMapKey( metric, dimensionValue );

		// Determine the last time we fetched datapoints for this metric and dimension
		Date lastDatapointDate = lastDatapointDateMap.get( datapointDateMapKey );
		if ( lastDatapointDate == null ) {
			lastDatapointDate = metricsReaderWriter.readMostRecentMetricDate( type, dimensionValue, metric.getName() );
		}

		// Build the request and execute it
		GetMetricStatisticsRequest request = cloudWatchRequest( metric, dimensionValue, lastDatapointDate );
		GetMetricStatisticsResult result = cloudWatchClient.getMetricStatistics( request );

		// Sort the datapoints in chronological order
		List<Datapoint> datapoints = result.getDatapoints();
		datapoints.sort( new DatapointComparator() );

		// Write the data points
		for ( Datapoint datapoint : datapoints ) {

			// Only care about data points that have happened after the last one
			if ( lastDatapointDate == null || datapoint.getTimestamp().after( lastDatapointDate ) ) {
				lastDatapointDate = datapoint.getTimestamp();
				metricsReaderWriter.writeMetric( type, dimensionValue, datapoint.getTimestamp(), metric.getName(), metric.getStatistic().getValueFromDatapoint( datapoint ) );
			}

		}

		// Update the date map
		lastDatapointDateMap.put( datapointDateMapKey, lastDatapointDate );

	}


	private String getDatapointDateMapKey( CloudWatchMetric metric, String dimensionValue ) {
		return metric.getName() + "_" + dimensionValue;
	}


	private GetMetricStatisticsRequest cloudWatchRequest( CloudWatchMetric metric, String dimensionValue, Date lastDatapointDate ) {

		// Work out the start and end time
		Date endDate = new Date();
		Date startDate = lastDatapointDate;
		if ( startDate == null || ( endDate.getTime() - startDate.getTime() ) > maxTimeDifference ) {

			startDate = new Date( endDate.getTime() - maxTimeDifference );

		} else if ( startDate.after( endDate ) ) {

			// We have a clock problem so back up the start date a few periods
			// TODO a better long term approach is probably to skip looking up metrics this time around
			startDate = new Date( endDate.getTime() - ( 1000 * requestPeriod * 5 ) );

		}

		// TODO this totally a hack to get this one metric to work since it requires the EngineName dimension to work, need to figure out a more long term solution
		if ( metric instanceof RDSCloudWatchMetric && metric.equals( RDSCloudWatchMetric.VolumeBytesUsed ) ) {
			return new GetMetricStatisticsRequest()
					.withStartTime( startDate )
					.withEndTime( endDate )
					.withPeriod( requestPeriod )
					.withNamespace( metric.getNamespace() )
					.withStatistics( metric.getStatistic().name() )
					.withDimensions( metric.getDimension( dimensionValue ), new Dimension().withName( "EngineName" ).withValue( "aurora" ) )
					.withMetricName( metric.getName() );
		} else {
			return new GetMetricStatisticsRequest()
					.withStartTime( startDate )
					.withEndTime( endDate )
					.withPeriod( requestPeriod )
					.withNamespace( metric.getNamespace() )
					.withStatistics( metric.getStatistic().name() )
					.withDimensions( metric.getDimension( dimensionValue ) )
					.withMetricName( metric.getName() );
		}
	}


	private class DatapointComparator implements Comparator<Datapoint> {


		@Override
		public int compare( Datapoint datapoint1, Datapoint datapoint2 ) {
			return datapoint1.getTimestamp().compareTo( datapoint2.getTimestamp() );
		}


	}
}
