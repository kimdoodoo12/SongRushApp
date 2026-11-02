# 005 Hosting facts (S-005)
Stack updated for D-029 (2026-11-02); decisions unchanged.

Researched 2026-11-02 by two `explorer` runs: a survey, then a gap-fill and verification pass against primary pages. This document is **facts only. No host is chosen; that is the user's decision (D-010, M-4).** "Unverified" means that no primary page confirmed the point.

## Questions (BACKLOG S-005)
For each host with a free or low tier that runs a long-lived server process (now the Spring Boot backend on JDK 17; the survey asked about Node):
1. Does it support WebSocket?
2. How much egress and ingress is included, and what is the overage price?
3. How much RAM and disk is available for a temporary audio cache?
4. Which regions are offered? The players are in Korea, so Seoul or Tokyo matters.
5. Does it sleep or cold-start?
6. Is a card required?

Compare B (WebSocket only, small egress) and C (WebSocket plus an audio cache, about 290-430 MB of egress per game; design 004).

## Findings

### F1. Host table
| Host | Seoul / Tokyo | Card | Free or smallest tier: RAM, disk | Egress included, then overage | Sleep / cold start | WebSocket |
|---|---|---|---|---|---|---|
| Fly.io | Tokyo `nrt` ✓, no Seoul | **required** ("All organizations ... require a credit card on file"); the 2 h / 7-day trial needs none | no free tier for new orgs ("New organizations don't have a free tier"); smallest always-on 256 MB at $2.19/mo; root disk size unverified | none listed; $0.02/GB NA/EU, **$0.04/GB APAC** | only if auto-stop is configured | not stated (VMs) |
| Render | neither; nearest Singapore | unverified | Free: 512 MB, 0.1 CPU (search snippet), ephemeral disk, no persistent disk | **5 GB/mo**, which free services count against; then $0.15/GB, or suspension if no payment method | **spins down after 15 min idle, about 1 min to wake** | yes |
| Railway | neither; nearest Singapore | unverified for sign-up ("requires the use of a post-paid card" on the plans page) | Free: 0.5 GB RAM, 0.5 GB volume, $1 credit/mo; Hobby $5/mo | $0.05/GB | only with Serverless enabled; the first request may 502 | not checked |
| Koyeb | Tokyo exists, but **the free instance is limited to Frankfurt or Washington, D.C.** | **required**, with a $29 pre-auth hold | Free: 512 MB, 0.1 vCPU, 2 GB SSD | conflicting: "not charged yet" vs. 100 GB then $0.04/GB | scale-to-zero status unclear | unverified |
| Oracle Always Free (A1) | **Seoul, Chuncheon and Tokyo**; Always Free compute must be in the home region chosen at sign-up | practically required (FAQ: "identity verification"; signup page 403) | 2 OCPU / 12 GB RAM total; 200 GB block storage | **10 TB/mo** | no sleep; **idle reclamation** if over 7 days CPU p95, network and memory (A1) are all under 20% (inferred that all three must hold) | VM |
| Google Cloud Run | Tokyo (Tier 1), Seoul (Tier 2) | **required** (a billing account is needed for the Free Tier) | 512 MiB default; the writable FS uses memory (inferred) | free tier includes 1 GB NA egress; other rates unverified | scales to zero (not confirmed on fetched pages); **WebSocket capped at 60 min per connection** | yes |
| Cloudflare Workers + Durable Objects | no pinned city (best-effort `apac-ne` hint) | unverified | 128 MB per isolate; free DO is SQLite-backed, 5 GB per account (paraphrased) | **egress free** on Workers Paid and R2 | DO hibernation | yes, via DO; **not a Node runtime, so it cannot run the Spring Boot backend** |
| Hetzner Cloud | neither; nearest Singapore `sin` | ID **or** card prepayment (search results) | CX23: 2 vCPU, 4 GB RAM, 40 GB, from €5.99 (EU; the page showed "currently unavailable"); Singapore types unverified | 20 TB (EU), then €1/TB | no sleep | VM |
| AWS Lightsail | **Seoul and Tokyo** | likely required (FAQ: "We ask for a credit or debit card"; a conflicting snippet was not verified) | $5: 0.5 GB RAM, 20 GB SSD; $3.50 IPv6-only: 512 MB | 1 TB, then $0.09/GB (US East example) | no sleep | VM |
| Vultr | Seoul and Tokyo per third-party sources (**unverified**; primary pages 403) | card or PayPal (search results) | about 1 GB RAM, 25 GB, about $5 (unverified) | overage $0.01/GB (docs, via search) | no sleep | VM |

Gaps: card rules for Render, Railway and Cloudflare; Fly's root disk size; all of Vultr's primary pages. Koyeb shows a "joining Mistral AI" banner; product continuity was not investigated.

### F2. Media and large-file rules found
- **Cloudflare.** The CDN section says non-Enterprise customers must use paid services such as the Developer Platform "to serve video and other large files via the CDN". Cloudflare may limit use "to serve ... a disproportionate percentage of pictures, audio files, or other large files".
  - The clause is **scoped to the CDN**. The Developer Platform terms say it "can be used to host content" and contain no large-file restriction.
  - **Unresolved:** whether a Free-plan Worker or R2 response counts as "the CDN".
- **Fly.io.** The acceptable use policy lists "Copyright violations (bots, torrents, mirrors)". It has no caching or bandwidth rule.
- The acceptable use pages of the other hosts were not checked.

## Monthly cost estimate
Assumptions:
- 10 players, 10 rounds, 10 files of 3.6 MB.
- Under C, egress per game is 0.36 GB: 7.2 GB at 20 games per month and 36 GB at 100. The backlog's 290-430 MB range spans 8-12 players.
- Under B, egress is about 20 MB per game.
- One always-on instance, a 30-day month, no tax.

| Host | B, 20 / 100 games | C, 20 / 100 games | Arithmetic and caveats |
|---|---|---|---|
| Fly.io, Tokyo | about $2.20 / $2.27 | about $2.48 / $3.63 | $2.19 + GB × $0.04 (APAC); 256 MB may be tight for a cache (inferred) |
| Render Free | $0 / $0 | about $0.33 / $4.65 | (7.2 − 5) × 0.15 and (36 − 5) × 0.15. **Not always-on**: it sleeps after 15 min. An always-on paid plan was not priced |
| Railway Hobby | about $6 / $6 | about $6.36 / $7.80 | compute about $6, assuming 0.5 GB RAM and 0.05 vCPU (the unit rates come from the first survey and were not re-verified) |
| Koyeb free | $0 | $0 | only Frankfurt and D.C.; egress terms conflict |
| Oracle A1 | $0 | $0 | 36 GB is far under 10 TB; idle reclamation risk |
| Hetzner CX23 | about €6 | about €6 | EU only; availability unstable; 20 TB included |
| Lightsail | $5 | $5 | 1 TB included; 0.5 GB RAM |
| Cloudflare Workers Paid | about $5 | about $5 | requires rewriting the backend away from Spring Boot; the CDN clause question is unresolved |
| Cloud Run | not computed | not computed | rates unverified |

## Observations for the decisions
- **Egress price.** Even on hosts that charge for egress, C costs at most about $5/month more than B at 100 games (Render about $4.65, Railway $1.80, Fly APAC $1.44). Among the hosts with verified prices, bandwidth cost does not argue against D-009 option C at friends-sized usage. Cloud Run and Koyeb are unverified.
- **For C, cache memory may matter more than egress.** 10 files of about 3.6 MB per active game is about 36-40 MB. Small instances have:
  - 256 MB on Fly's smallest;
  - 512 MB on Render, Koyeb and Cloud Run;
  - 0.5 GB on Railway Free and Lightsail $5.

  These would hold one or two concurrent games in memory alongside the server process (inferred when the server was Node; not re-checked for the JVM). A disk cache avoids this on hosts with a disk; design 011 uses the disk cache `data/cache/audio/`.
- **Facts that bear on D-010.** Every host appears here; this is not a ranking:
  - **Region near Korea:** Seoul or Tokyo is available on Fly (Tokyo), Oracle, Cloud Run and Lightsail, and on Vultr (unverified). Render, Railway and Hetzner reach Singapore at best. Koyeb's free tier is EU or US only.
  - **Card:** required on Fly, Koyeb and Cloud Run; practically required on Oracle; likely required on Lightsail; card or PayPal on Vultr. Hetzner takes an ID or a card prepayment. Unverified on Render, Railway and Cloudflare.
  - **Sleep or limits:**
    - Render sleeps after 15 min and takes about 1 min to wake.
    - Cloud Run caps a WebSocket at 60 min, and multiple instances share no room state (inferred; a single max instance avoids that).
    - Oracle may reclaim an idle VM.
    - Cloudflare is not a Node runtime and cannot run the Spring Boot backend.
    - Hetzner's small plans showed "currently unavailable".
    - Lightsail and Railway Free have 0.5 GB of RAM.
- **Unmeasured:** AnimeThemes TTFB from any of these regions. Design 003 suggests the delay is server-side.

## Decision
None by agents. This feeds **D-010** (hosting choice, needs the user's decision, non-blocking until M-4).

The questions for the user are which of these they accept:
- a credit card;
- a cold start of about 1 min;
- idle reclamation;
- a rewrite of the backend away from Spring Boot;
- a region outside Seoul or Tokyo.

The candidate set is the table above. No recommendation is given, because the answer depends on those preferences.

## Resulting items
- **D-010** is added to BACKLOG "Needs decision" and to PROGRESS.
- No implementation items. M-4 planning re-verifies the cells marked "unverified" for the chosen host.

## Review changes (round 1)
| Finding | Change |
|---|---|
| H1: D-010 claimed but missing | Added to BACKLOG and PROGRESS with its decision criteria |
| H2: regions and cache capacity missing | Seoul/Tokyo column and RAM/disk column added via a verification run; Fly cost redone at APAC rates |
| H3: card asymmetry implied a ranking | Card status listed for every host; the observations cover every host |
| M1: "most hosts" is wrong | Restated as a dollar bound; cache memory is noted as the likelier constraint |
| M2: Cloudflare clause applied inconsistently | Scoped to the CDN everywhere; the Workers/R2 question is marked unresolved |
| M3: Render row mixed assumptions | Marked as not always-on |
| M4: inferences stated as confirmed | "confirms" softened; Cloud Run state sharing marked inferred |
| Nits | Railway $7.80; unverified unit rates flagged; Hetzner figure from the verified page |
