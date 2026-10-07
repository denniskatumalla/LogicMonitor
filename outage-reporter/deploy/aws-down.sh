#!/usr/bin/env bash
# Terminates the Outage Reporter instance. With --all, also deletes the security
# group and key pair (and the local .pem). Asks before doing anything.
#   deploy/aws-down.sh [--all]
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

export AWS_REGION=${AWS_REGION:-us-east-1} AWS_PAGER=""
name=outage-reporter
key_name=outage-reporter
key_file=$HOME/.ssh/${key_name}.pem
sg_name=outage-reporter
all=0
[[ ${1:-} == --all ]] && all=1

instances=$(aws ec2 describe-instances \
  --filters Name=tag:Name,Values="$name" Name=instance-state-name,Values=pending,running,stopping,stopped \
  --query 'Reservations[].Instances[].InstanceId' --output text)

echo "Region $AWS_REGION. This will terminate: ${instances:-no instances}"
(( all )) && echo "and delete security group $sg_name, key pair $key_name and $key_file"
read -r -p "Type 'yes' to continue: " answer
[[ $answer == yes ]] || { echo "Nothing changed."; exit 0; }

if [[ -n $instances ]]; then
  # shellcheck disable=SC2086
  aws ec2 terminate-instances --instance-ids $instances >/dev/null
  # shellcheck disable=SC2086
  aws ec2 wait instance-terminated --instance-ids $instances
  echo "terminated $instances"
fi

if (( all )); then
  sg=$(aws ec2 describe-security-groups --filters Name=group-name,Values="$sg_name" \
         --query 'SecurityGroups[0].GroupId' --output text)
  [[ $sg != None ]] && aws ec2 delete-security-group --group-id "$sg" && echo "deleted $sg_name"
  aws ec2 delete-key-pair --key-name "$key_name" && echo "deleted key pair $key_name"
  rm -f "$key_file" && echo "removed $key_file"
fi
rm -f build/instance.env
