#! /bin/bash

host=$1
authtoken=$2
instanceid=$3
warning=$4
critical=$5

# This function prints out an ES query accepting 2 parameters:
# $1 = from (time in ms since epoch)
# $2 = to (time in ms since epoch)
#  example: query=$(get_query $(get_epoch_in_ms 'now - 1 minute') $(get_epoch_in_ms 'now'))
function get_query {
	local from="$1"
	local to="$2"

	cat <<-EOF
	{
	  "query": {
		"filtered": {
		  "query": {
			"bool": {
			  "should": [
				{
				  "query_string": {
					"query": "*"
				  }
				}
			  ]
			}
		  },
		  "filter": {
			"bool": {
			  "must": [
				{
				  "range": {
					"@timestamp": {
					  "from": $from,
					  "to": $to
					}
				  }
				},
				{
				  "fquery": {
					"query": {
					  "query_string": {
						"query": "instance_id:(\"$instanceid\")"
					  }
					},
					"_cache": true
				  }
				},
				{
				  "fquery": {
					"query": {
					  "query_string": {
						"query": "metricset.name:(\"cpu\")"
					  }
					},
					"_cache": true
				  }
				}
			  ]
			}
		  }
		}
	  },
	  "highlight": {
		"fields": {},
		"fragment_size": 2147483647,
		"pre_tags": [
		  "@start-highlight@"
		],
		"post_tags": [
		  "@end-highlight@"
		]
	  },
	  "size": 500,
	  "sort": [
		{
		  "@timestamp": {
			"order": "desc",
			"ignore_unmapped": true
		  }
		}
	  ]
	}
	EOF
}

# This function hits ES hosts with a specified query
function run_query {
	local query="$1"
	local today="$2"
	local yesterday="$3"
	local endpoint="https://$host/metricbeat-$today,metricbeat-$yesterday/_search?timeout=3m"

	local statuscode=$(curl -s -o /dev/null -w "%{http_code}" "https://$host/metricbeat-$today" -H "Authorization: Basic $authtoken")
	if [ "$statuscode" == "404" ];then
		endpoint="https://$host/metricbeat-$yesterday/_search?timeout=3m"
	fi

	curl -sm 10 "$endpoint" \
		-H "Authorization: Basic $authtoken" \
		-H 'Content-Type: application/json;charset=UTF-8' \
		-H 'Accept: application/json, text/plain, */*' \
		--data-binary @- <<< "$query" |
			# Get the result in CSV
			jq -r '.hits.hits[]._source | [.system.cpu.idle.pct] | @csv'
}

# This function returns epoch in ms from data_string
# Example: get_epoch_in_ms 'now - 10 minutes'
function get_epoch_in_ms {
	# Here we just append 3 zeros epoch in seconds
	local data_string="$1"
	echo $(date -d "$data_string" +%s)000
}

function clean_input {
	local input="$1"
	# Remove double quotes, we're not going to need them
	sed -re 's/"//g' <<< "$input"  
}

# Creates message with line break "\n" if terminal or "<br/>" for better html visualization
function log_msg {
    if [ -t 1 ];then
            linebreak="\n"
    else
            linebreak="<br/>"
    fi

    if [ -z "$msg" ];then
        msg="$@"
    else
        msg="${msg}${linebreak}${@}"
    fi
}

# Get correct exit code based on message
function get_exit_code {
    msg1=$(echo -ne "$msg" | sed -e 's/<br\/>/\n/g')
    echo -e "$msg1" | grep -q "^CRITICAL" 
    if [ "$?" == 0 ];then
        export exit_code=2
        return $exit_code
    fi
    echo -e "$msg1" | grep -q "^WARNING"
    if [ "$?" == 0 ];then
        export exit_code=1
        return $exit_code
    fi
    echo -e "$msg1" | grep -q "^UNKNOWN"
    if [ "$?" == 0 ];then
        export exit_code=3
        return $exit_code
    fi
    export exit_code=0
    return
}

function print_msg_and_exit {
        echo -e "$msg"
        get_exit_code
        exit "$exit_code"
}

# We are using this function because test in bash can only test integers
function check_exp {
	local exp=$1
	local result=$(bc <<< "$exp")
	test "$result" -eq 1 
}

timeinterval="20 minute"
query=$(get_query $(get_epoch_in_ms "now - $timeinterval") $(get_epoch_in_ms 'now'))

input=$(run_query "$query" $(date +"%Y.%m.%d") $(date --date="yesterday" +"%Y.%m.%d"))

test -z "$input" && {
	log_msg "UNKNOWN: Plugin failed to retrieve input"
	print_msg_and_exit
}

counter=0
cpuidletotal=0
while read line; do
	eval $(awk -F, '{printf "metric_value=%s\n",$1}' <<< "$line")
	cpuidletotal=$(echo "$cpuidletotal + $metric_value" | bc)
	counter=$(echo "$counter + 1" | bc)
done < <( clean_input "$input")

cpuidleavg=$(echo "scale=4; $cpuidletotal / $counter" | bc)
cpuidleavg=$(echo "scale=2; $cpuidleavg * 100" | bc)
cpuavg=$(echo "100 - $cpuidleavg" | bc)

if  check_exp "$cpuavg <= $warning" ;then
	log_msg "OK: CPU Utilization = average of $cpuavg% over $timeinterval"
elif check_exp "$cpuavg > $warning && $cpuavg <= $critical" ;then
	log_msg "WARNING: CPU Utilization = average of $cpuavg% over $timeinterval"
elif check_exp "$cpuavg > $critical"  ;then
	log_msg "CRITICAL: CPU Utilization = average of $cpuavg% over $timeinterval"
else 
	log_msg "UNKNOWN: Could not determine CPU utilization"
	echo $input > /var/tmp/check-aws-ec2-cpu-via-es-mb-input.txt
fi

print_msg_and_exit
