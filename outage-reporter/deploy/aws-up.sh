#!/usr/bin/env bash
# Creates (or reuses) everything Outage Reporter needs in AWS, then deploys the jar:
# budget, key pair, security group, EC2 instance with build/user-data.sh, push.sh.
# Safe to re-run: each step reuses what already exists.
#   deploy/aws-up.sh [--budget-email you@example.com] [--no-deploy]
# Sign in first with `aws login` (no access keys needed). Region: AWS_REGION, default us-east-1.
set -euo pipefail
# Run from the repository root; paths below are relative to it.
cd "$(dirname "${BASH_SOURCE[0]}")/.."

# An empty AWS_PAGER stops the CLI paging its output.
export AWS_REGION=${AWS_REGION:-us-east-1} AWS_PAGER=""
name=outage-reporter
key_name=outage-reporter
key_file=$HOME/.ssh/${key_name}.pem
sg_name=outage-reporter
instance_type=c7i-flex.large
budget_email=""
deploy=1

# Command-line options.
while (( $# )); do
  case $1 in
    --budget-email) budget_email=${2:?--budget-email needs an address}; shift 2 ;;
    --no-deploy) deploy=0; shift ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

# Prints a section heading.
step() { printf '\n== %s\n' "$*"; }

step "Identity"
# Show which identity and region the CLI is using; refuse the root user.
arn=$(aws sts get-caller-identity --query Arn --output text)
account=$(aws sts get-caller-identity --query Account --output text)
echo "$arn (region $AWS_REGION)"
if [[ $arn == *":root" ]]; then
  echo "Signed in as the root user. Run 'aws login' as your IAM user instead." >&2; exit 1
fi

step "Budget"
# Optional cost guard: a $20/month AWS Budget that emails at 50% of actual spend.
if [[ -z $budget_email ]]; then
  echo "skipped (pass --budget-email to create a \$20 monthly budget with a 50% alert)"
elif aws budgets describe-budget --account-id "$account" --budget-name demo-budget >/dev/null 2>&1; then
  echo "demo-budget exists"
else
  aws budgets create-budget --account-id "$account" \
    --budget '{"BudgetName":"demo-budget","BudgetLimit":{"Amount":"20","Unit":"USD"},"TimeUnit":"MONTHLY","BudgetType":"COST"}' \
    --notifications-with-subscribers "[{\"Notification\":{\"NotificationType\":\"ACTUAL\",\"ComparisonOperator\":\"GREATER_THAN\",\"Threshold\":50,\"ThresholdType\":\"PERCENTAGE\"},\"Subscribers\":[{\"SubscriptionType\":\"EMAIL\",\"Address\":\"$budget_email\"}]}]"
  echo "created demo-budget (\$20/month, email at 50%)"
fi

step "Key pair"
# SSH key pair. AWS keeps only the public key; the private key is returned once,
# at creation, and saved under ~/.ssh readable only by this user.
if aws ec2 describe-key-pairs --key-names "$key_name" >/dev/null 2>&1; then
  [[ -f $key_file ]] || { echo "Key pair $key_name exists in AWS but $key_file is missing. Delete it in EC2 → Key Pairs, or copy the .pem there." >&2; exit 1; }
  echo "reusing $key_name ($key_file)"
else
  [[ -e $key_file ]] && { echo "$key_file already exists locally; refusing to overwrite it." >&2; exit 1; }
  mkdir -p "$HOME/.ssh"
  (umask 077; aws ec2 create-key-pair --key-name "$key_name" --key-type rsa --key-format pem \
     --query KeyMaterial --output text > "$key_file")
  chmod 600 "$key_file"
  echo "created $key_name, saved to $key_file"
fi

step "Security group"
# Security group in the default VPC. Only the inbound rules added below are allowed;
# outbound stays open (the AWS default).
vpc=$(aws ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
[[ $vpc == None ]] && { echo "No default VPC in $AWS_REGION." >&2; exit 1; }
sg=$(aws ec2 describe-security-groups --filters Name=vpc-id,Values="$vpc" Name=group-name,Values="$sg_name" \
       --query 'SecurityGroups[0].GroupId' --output text)
if [[ $sg == None ]]; then
  sg=$(aws ec2 create-security-group --group-name "$sg_name" --description "Outage Reporter demo" \
         --vpc-id "$vpc" --query GroupId --output text)
  echo "created $sg_name ($sg)"
else
  echo "reusing $sg_name ($sg)"
fi
# This laptop's public IP, so inbound access can be limited to it.
my_ip=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
# Adds one inbound TCP rule. A rule that already exists counts as success, so re-runs are clean.
allow() {  # port cidr description
  local out
  if out=$(aws ec2 authorize-security-group-ingress --group-id "$sg" \
             --ip-permissions "IpProtocol=tcp,FromPort=$1,ToPort=$1,IpRanges=[{CidrIp=$2,Description=\"$3\"}]" 2>&1); then
    echo "  allowed $1/tcp from $2"
  elif [[ $out == *InvalidPermission.Duplicate* ]]; then
    echo "  $1/tcp from $2 already allowed"
  else
    echo "$out" >&2; return 1
  fi
}
# SSH, the customer pages and HTTPS, each from this IP only. For the external web
# check, add LogicMonitor's public checkpoint ranges on 8080 by hand (not done here).
# JMX (9010), SNMP (161) and the admin port (8081) are never opened: the collector
# runs on this VM and reaches them locally.
allow 22   "$my_ip/32" "SSH from my IP"
allow 8080 "$my_ip/32" "Customer pages (add LM checkpoint ranges for the external web check)"
allow 8443 "$my_ip/32" "HTTPS from my IP"

step "Instance"
# Reuse a live instance tagged Name=outage-reporter (starting it if stopped);
# otherwise launch a new one.
read -r instance state < <(aws ec2 describe-instances \
  --filters Name=tag:Name,Values="$name" Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[0].[InstanceId,State.Name] | [0]' --output text)
if [[ -n ${instance:-} && $instance != None ]]; then
  echo "reusing $instance ($state)"
  if [[ $state == stopped || $state == stopping ]]; then
    aws ec2 wait instance-stopped --instance-ids "$instance"
    aws ec2 start-instances --instance-ids "$instance" >/dev/null
    echo "starting it"
  fi
else
# Regenerate build/user-data.sh, then look up the latest Amazon Linux 2023 AMI
# from the public SSM parameter AWS maintains.
  ./deploy/make-user-data.sh
  ami=$(aws ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
          --query Parameter.Value --output text)
# Launch with the user data (the first-boot bootstrap), a 30 GB gp3 root volume,
# IMDSv2 required (instance metadata only with a session token) and Name/Application tags.
  instance=$(aws ec2 run-instances \
    --image-id "$ami" --instance-type "$instance_type" --key-name "$key_name" \
    --security-group-ids "$sg" \
    --user-data file://build/user-data.sh \
    --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30,VolumeType=gp3,DeleteOnTermination=true}' \
    --metadata-options HttpTokens=required,HttpEndpoint=enabled \
    --tag-specifications "ResourceType=instance,Tags=[{Key=Name,Value=$name},{Key=Application,Value=$name}]" \
                         "ResourceType=volume,Tags=[{Key=Name,Value=$name},{Key=Application,Value=$name}]" \
    --query 'Instances[0].InstanceId' --output text)
  echo "launched $instance ($instance_type, $ami)"
fi
# Wait for both EC2 status checks, then save the address details to build/instance.env.
echo "waiting for status checks to pass (3-5 minutes)..."
aws ec2 wait instance-status-ok --instance-ids "$instance"
read -r ip dns < <(aws ec2 describe-instances --instance-ids "$instance" \
  --query 'Reservations[0].Instances[0].[PublicIpAddress,PublicDnsName]' --output text)
mkdir -p build
printf 'INSTANCE_ID=%s\nPUBLIC_IP=%s\nPUBLIC_DNS=%s\nKEY_FILE=%s\n' "$instance" "$ip" "$dns" "$key_file" > build/instance.env
echo "$instance is up: $ip ($dns), saved to build/instance.env"

# Copy the jar to the VM and start the service (skipped with --no-deploy).
if (( deploy )); then
  step "Deploy"
  ./deploy/push.sh "ec2-user@$ip" "$key_file"
fi

step "Done"
echo "Customer page:  http://$ip:8080/"
echo "SSH:            ssh -i $key_file ec2-user@$ip"
echo "LM properties:  ssh -i $key_file ec2-user@$ip sudo cat /root/outage-reporter-lm-properties.txt"
