package io.codearcs.candlestack.aws.elasticbeanstalk;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.Instance;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalk;
import com.amazonaws.services.elasticbeanstalk.AWSElasticBeanstalkClientBuilder;
import com.amazonaws.services.elasticbeanstalk.model.EnvironmentDescription;

import io.codearcs.candlestack.CandlestackException;
import io.codearcs.candlestack.CandlestackPropertiesException;
import io.codearcs.candlestack.ScriptFetcher;
import io.codearcs.candlestack.aws.GlobalAWSProperties;
import io.codearcs.candlestack.aws.ec2.EC2CloudWatchMetric;
import io.codearcs.candlestack.aws.ec2.EC2MetricbeatMetric;
import io.codearcs.candlestack.aws.ec2.EC2Util;
import io.codearcs.candlestack.nagios.HostMonitorLookup;
import io.codearcs.candlestack.nagios.object.commands.Command;
import io.codearcs.candlestack.nagios.object.hosts.Host;
import io.codearcs.candlestack.nagios.object.hosts.HostGroup;


public class EBHostMonitorLookup implements HostMonitorLookup {

	private static final String EC2_COMMAND_SUFFIX = "-eb";

	private AWSElasticBeanstalk beanstalkClient;

	private AmazonEC2 ec2Client;

	private Set<String> contactGroups;

	private String environmentNamePrefix, environmentNameRegex;

	private Set<EC2CloudWatchMetric> ec2CloudWatchMetrics;

	private Set<EC2MetricbeatMetric> ec2MetricbeatMetrics;

	private Set<EBCloudWatchMetric> ebCloudWatchMetrics;

	private long newResourceMonitorDelayMillis;


	public EBHostMonitorLookup( Set<String> contactGroups ) throws CandlestackPropertiesException {

		this.contactGroups = contactGroups;

		environmentNamePrefix = GlobalAWSProperties.getEBEnvrionmentNamePrefix();
		environmentNameRegex = GlobalAWSProperties.getEBEnvrionmentNameRegex();

		newResourceMonitorDelayMillis = TimeUnit.MINUTES.toMillis( GlobalAWSProperties.getEBNewResourceMonitorDelay() );

		ec2CloudWatchMetrics = GlobalAWSProperties.getEC2CloudwatchMetricsToMonitor();
		ec2MetricbeatMetrics = GlobalAWSProperties.getEC2MetricbeatMetricsToMonitor();
		ebCloudWatchMetrics = GlobalAWSProperties.getEBCloudwatchMetricsToMonitor();

		String region = GlobalAWSProperties.getRegion();
		beanstalkClient = AWSElasticBeanstalkClientBuilder.standard().withRegion( region ).build();
		ec2Client = AmazonEC2ClientBuilder.standard().withRegion( region ).build();

	}


	@Override
	public String getName() {
		return EBUtil.TYPE_NAME;
	}


	@Override
	public Map<String, InputStream> getMonitorResources() throws CandlestackException {

		Map<String, InputStream> resourceMap = new HashMap<>();

		for ( EC2CloudWatchMetric metric : ec2CloudWatchMetrics ) {
			resourceMap.put( metric.getScriptFileName(), ScriptFetcher.fetchInputStream( metric.getScriptFileName() ) );
		}

		for ( EC2MetricbeatMetric metric : ec2MetricbeatMetrics ) {
			resourceMap.put( metric.getScriptFileName(), ScriptFetcher.fetchInputStream( metric.getScriptFileName() ) );
		}

		for ( EBCloudWatchMetric metric : ebCloudWatchMetrics ) {
			resourceMap.put( metric.getScriptFileName(), ScriptFetcher.fetchInputStream( metric.getScriptFileName() ) );
		}

		return resourceMap;

	}


	@Override
	public List<Command> getMonitorCommands( String relativePathToMonitorResource ) {

		List<Command> commands = new ArrayList<>();

		for ( EC2CloudWatchMetric metric : ec2CloudWatchMetrics ) {
			commands.add( metric.getMonitorCommand( EC2_COMMAND_SUFFIX, relativePathToMonitorResource ) );
		}

		for ( EC2MetricbeatMetric metric : ec2MetricbeatMetrics ) {
			commands.add( metric.getMonitorCommand( EC2_COMMAND_SUFFIX, relativePathToMonitorResource ) );
		}

		for ( EBCloudWatchMetric metric : ebCloudWatchMetrics ) {
			commands.add( metric.getMonitorCommand( relativePathToMonitorResource ) );
		}

		return commands;

	}


	@Override
	public List<HostGroup> lookupHostsToMonitor() throws CandlestackPropertiesException {

		List<HostGroup> hostGroups = new ArrayList<>();

		// Figure out the minimum launch age for the environment to monitored
		Date minLaunchAge = new Date( System.currentTimeMillis() - newResourceMonitorDelayMillis );

		// Go ahead and fetch the EC2 instances in bulk rather than making calls for the individual environments
		Map<String, List<Instance>> environmentInstanceMap = EBUtil.lookupInstances( ec2Client, environmentNamePrefix, environmentNameRegex );

		// Look through the environments for eligible ones
		for ( EnvironmentDescription environment : beanstalkClient.describeEnvironments().getEnvironments() ) {

			// Skip over ineligible environments
			if ( !EBUtil.isEnvironmentEligible( environment, environmentNamePrefix, environmentNameRegex ) ) {
				continue;
			}

			// Make sure the environment is old enough to be monitored
			if ( minLaunchAge.before( environment.getDateCreated() ) ) {
				continue;
			}

			// Get the notificaiton period override if there is one
			String notificationPeriod = GlobalAWSProperties.getEBServiceNotificationPeriod( environment.getEnvironmentName() );

			// Define the host group object for this environment
			HostGroup hostGroup = new HostGroup( environment.getEnvironmentName(), environment.getDescription() );
			hostGroups.add( hostGroup );

			// Add the host for the environment
			Host host = new Host( environment.getEnvironmentName(), environment.getDescription(), environment.getEndpointURL(), contactGroups );
			for ( EBCloudWatchMetric metric : ebCloudWatchMetrics ) {
				host.addService( metric.getService( environment.getEnvironmentName(), contactGroups ) );
			}
			hostGroup.addHost( host );

			// Add the associated hosts
			List<Instance> instances = environmentInstanceMap.get( environment.getEnvironmentName() );
			if ( instances != null ) {
				for ( Instance instance : instances ) {
					// Make sure the instance is old enough to be monitored
					if ( minLaunchAge.after( instance.getLaunchTime() ) ) {
						hostGroup.addHost( createHostFromInstance( instance, notificationPeriod ) );
					}
				}
			}

		}

		return hostGroups;

	}


	private Host createHostFromInstance( Instance instance, String notificationPeriod ) throws CandlestackPropertiesException {

		// Lookup the alias and create the host object
		String alias = EC2Util.getTagValue( instance, "Name" );
		Host host = new Host( instance.getInstanceId(), alias, instance.getPublicIpAddress(), contactGroups );

		for ( EC2CloudWatchMetric metric : ec2CloudWatchMetrics ) {
			host.addService( metric.getService( EC2_COMMAND_SUFFIX, notificationPeriod, instance.getInstanceId(), contactGroups ) );
		}

		for ( EC2MetricbeatMetric metric : ec2MetricbeatMetrics ) {
			host.addService( metric.getService( EC2_COMMAND_SUFFIX, notificationPeriod, instance.getInstanceId(), contactGroups ) );
		}

		return host;

	}
}
