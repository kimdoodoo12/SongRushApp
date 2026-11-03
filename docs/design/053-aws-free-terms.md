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

## Options
- **(a) EC2 t3.micro on the Free plan** (as D-065 meant).
  - Free for up to 6 months. The deploy kit's `-Xmx384m`, SerialGC and 1 GB swap fit 1 GiB.
  - Afterwards, either upgrade to the Paid plan (about $16/month) or move.
- **(b) EC2 t3.small on the Free plan.**
  - 2 GiB gives more memory room, but the $100 credit lasts about 4 months, or about 6 with the earned $100.
  - Afterwards about $25/month.
- **(c) Lightsail Micro or Small.** The cheapest after any free period ($7 or $12, all-in). It needs a Paid account, and its Free plan coverage is unverified.

The deploy kit (I-004) targets Ubuntu 24.04 with SSH and works on any of these. The choice does not block B-009, I-004, I-005 or I-006. Only the runbook's console steps and cost section depend on it.

## Decision
- **Needs user decision (cost): D-068.** Recommended: (a). It is free for about 6 months and fits the kit's memory settings. Before the plan ends, decide between upgrading (about $16/month) and moving to Lightsail Micro ($7/month).
- **The runbook (I-006)** must state the 6-month or credit limit and the automatic account closure (data deleted 90 days later) in plain words, with a reminder to decide before month 5.

## Resulting items
- No new Ready items.
- I-006's cost section uses the figures above.
- D-068 goes to Needs decision.
