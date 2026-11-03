# 053 AWS free terms (S-003)

## Questions
1. What does a new AWS account (created 2026-11) get, compared with D-065's assumption of "12 months free, t3.micro, 30 GB EBS"?
2. What would this workload cost in ap-northeast-2 while the free terms last, and afterwards?
3. Is there a clearly cheaper option within AWS?

## Findings (checked 2026-11-04; the pricing pages show no dates)
- **Free plan for new accounts** (https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html):
  - Credits: "If you are new to AWS, you receive USD $100 in credits after you create an account regardless of your account plan. You can also earn up to an additional USD $100 in credits by completing activities."
  - Duration: "Your free account plan ends after six months or when your credits are fully used - whichever occurs first."
  - At the end: "After your free account plan expires, your account closes automatically, and you lose access to your resources and data. AWS retains your content for 90 days ... you can upgrade to a Paid account plan with pay-as-you-go pricing within 90 days."
  - https://aws.amazon.com/free/ says the same: "You won't be charged unless you convert to a Paid plan."
- **EC2 eligibility** (https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html):
  - For accounts created on or after 2025-07-15, the eligible types are `t3.micro`, `t3.small`, `t4g.micro`, `t4g.small`, `c7i-flex.large` and `m7i-flex.large`. Usage is paid from the credits.
  - The old "750 h/month for 12 months" applies only to older accounts. **D-065's assumption no longer holds.**
- **Prices in Seoul** (Linux on-demand, from a third-party aggregator, https://aws-pricing.com/ap-northeast-2.html; AWS's own page lists no per-type figures):
  - t3.micro (1 GiB): $0.013/h, about $9.49/month.
  - t3.small (2 GiB): $0.026/h, about $18.98/month.
  - gp3: $0.0912/GB-month.
- **Public IPv4** (https://aws.amazon.com/vpc/pricing/): "Hourly charge for In-use Public IPv4 Address $0.005", about $3.65/month. The page gives no Free plan waiver, so this comes out of the credits.
- **Data transfer out** (https://aws.amazon.com/ec2/pricing/on-demand/): "100 GB of free data transfer out to the internet free each month".
  - Usage: a game is at most about 400 MB (8 players × 10 songs × about 5 MB), so a few games a week stay far below this.
- **Lightsail** (https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html):
  - Micro (1 GB, 40 GB SSD, 2 TB transfer, IPv4 included): $7/month.
  - Small (2 GB, 60 GB, 3 TB): $12/month.
  - Not verified: whether the Free plan allows Lightsail.

## Cost estimate (Seoul, 730 h/month)

| | t3.micro + 30 GB gp3 + IPv4 | t3.small + 30 GB gp3 + IPv4 | Lightsail Micro | Lightsail Small |
|---|---|---|---|---|
| Per month | about $15.9 | about $25.4 | $7 | $12 |
| $100 credit lasts | about 6 months (capped at 6) | about 4 months | n/a | n/a |
| Out of pocket during the Free plan | $0 | $0 | (unverified) | (unverified) |

## Options (revised after review: the sign-up plan is a separate choice)
The free-tier-plans comparison table gives both plans the same credits ("Receive USD $100 sign up credit and earn up to $100 in additional credits"). Free plan: "Account closes when credits are depleted or when the plan duration ends". Paid plan: "Account doesn't close when credits are depleted".

| Sign-up plan x host | Monthly cost after credits | Main risk |
|---|---|---|
| **Free plan + EC2 t3.micro** | about $15.9, but only if upgraded within 90 days of closure | Closes automatically at 6 months or when credits run out. With $100 the slack is only about $4.7: an extra test instance, a snapshot, a stopped instance still paying for EBS and IPv4, or many 30-song games (about 1.2 GB each) close it early. 90 days after closure the instance, the EBS volume and the 5.1 GB audio are deleted. |
| **Paid plan + EC2 t3.micro + Budgets alert** | about $14.6 with a 16 GB volume | No closure. Charges start once the credits are used, so an AWS Budgets alert (for example at $1 of actual spend) is required. |
| Paid plan + EC2 t3.small | about $24 | As above. More memory headroom; the credits last about 4 months. |
| Paid plan + Lightsail Micro | $7, with the IP, disk and 2 TB transfer included (the allowance "can vary by Region"; Seoul overage $0.13/GB) | Unverified: whether the sign-up credits apply to Lightsail. |

Notes:
- **Disk (review M1).** About 12 GB is needed: 5.1 GB of audio, Ubuntu, a JDK, Caddy and 1 GB of swap. A 16 GB gp3 volume costs about $1.46/month instead of $2.74 for 30 GB.
- **Moving later (review M2).** Moving from EC2 to Lightsail means a new server, re-uploading 5.1 GB, different firewall steps and a new IP (so a new sslip.io name). Upgrading in place keeps everything.
- **Credit activities (review H2).** The activities that earn extra credits include "Launch an instance using Amazon EC2" and "Set up a cost budget using AWS Budgets". The runbook does both anyway, so it names them.
- **Reversibility.** The sign-up plan can be changed from Free to Paid. Data deleted 90 days after a closure cannot be recovered.
- The deploy kit (I-004) targets Ubuntu 24.04 over SSH and works on any of these hosts. The choice does not block B-009, I-004, I-005 or I-006; only the runbook's console steps and its cost section depend on it.

## Decision
- **Needs user decision (cost, account): D-068.** **Recommended: Paid plan + EC2 t3.micro with a 16 GB gp3 volume + a Budgets alert.**
  - It keeps D-065's EC2 choice.
  - It is free while the credits last (about 6-7 months; longer with the earned credits).
  - It never closes on its own.
  - After the credits run out it costs about $15/month. At that point the owner can stay, or move to Lightsail Micro ($7).
- **The runbook (I-006)** states in plain words:
  - the chosen plan's limits;
  - the Budgets alert step;
  - the two credit-earning activities;
  - for the Free plan, the automatic closure and the deletion 90 days later.

## Review changes (round 1)
- **High.**
  - The Paid sign-up plan is now an option, and it is the recommended one.
  - The Free plan's thin credit slack and its early-closure triggers are listed.
  - The Budgets alert is required.
- **Medium.**
  - The disk is sized at 16 GB.
  - The cost of moving later is stated.
- **Nits.**
  - Lightsail coverage is marked unverified.
  - The Lightsail allowance varies by Region.
  - A 30-song game is about 1.2 GB.

## Resulting items
- No new Ready items.
- I-006's console steps and cost section follow D-068.
- D-068 goes to Needs decision.
