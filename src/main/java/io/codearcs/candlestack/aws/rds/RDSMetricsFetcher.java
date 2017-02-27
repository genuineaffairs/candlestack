package io.codearcs.candlestack.aws.rds;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.rds.AmazonRDS;
import com.amazonaws.services.rds.AmazonRDSClientBuilder;
import com.amazonaws.services.rds.model.DBInstance;
import com.amazonaws.services.rds.model.DescribeDBInstancesResult;

import io.codearcs.candlestack.CandlestackException;
import io.codearcs.candlestack.MetricsFetcher;
import io.codearcs.candlestack.aws.GlobalAWSProperties;
import io.codearcs.candlestack.aws.cloudwatch.CloudWatchAccessor;


public class RDSMetricsFetcher extends MetricsFetcher {

	private static final Logger LOGGER = LoggerFactory.getLogger( RDSMetricsFetcher.class );

	private Set<RDSCloudWatchMetric> cloudWatchMetrics;

	private AmazonRDS rdsClient;

	private String dbInstancePrefix, dbInstanceRegex;

	private CloudWatchAccessor cloudWatchAccessor;


	public RDSMetricsFetcher() throws CandlestackException {
		super( RDSUtil.TYPE_NAME, GlobalAWSProperties.getRDSMetricsFetcherSleep() );

		dbInstancePrefix = GlobalAWSProperties.getRDSDBInstancePrefix();
		dbInstanceRegex = GlobalAWSProperties.getRDSDBInstanceRegex();

		cloudWatchMetrics = GlobalAWSProperties.getRDSCloudwatchMetricsToFetch();

		rdsClient = AmazonRDSClientBuilder.standard().withRegion( GlobalAWSProperties.getRegion() ).build();

		cloudWatchAccessor = CloudWatchAccessor.getInstance();
	}


	@Override
	public void fetchMetrics() {

		try {

			Set<String> replicaInstances = RDSUtil.getReplicaInstances( rdsClient );

			DescribeDBInstancesResult dbInstanceResults = rdsClient.describeDBInstances();
			for ( DBInstance dbInstance : dbInstanceResults.getDBInstances() ) {

				String dbInstanceId = dbInstance.getDBInstanceIdentifier();
				RDSType rdsType = RDSType.getTypeFromEngine( dbInstance.getEngine() );
				if ( !RDSUtil.isDBInstanceEligible( dbInstanceId, dbInstancePrefix, dbInstanceRegex, rdsType ) ) {
					continue;
				}

				for ( RDSCloudWatchMetric cloudWatchMetric : cloudWatchMetrics ) {
					if ( cloudWatchMetric.isRDSTypeSupported( rdsType ) && ( !cloudWatchMetric.isReplicaOnlyMetric() || replicaInstances.contains( dbInstanceId ) ) ) {
						cloudWatchAccessor.lookupAndSaveMetricData( cloudWatchMetric, dbInstanceId, RDSUtil.TYPE_NAME );
					}
				}

			}

		} catch ( CandlestackException e ) {
			LOGGER.error( "RDSMetricsFetcher encountered an error while trying to fetch metrics", e );
		}

	}


	@Override
	public void close() {}


}
