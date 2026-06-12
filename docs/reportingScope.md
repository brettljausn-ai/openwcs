Reporting Requirements

Reporting is it's own cateory in the main menu (sidebar).
The below requirements are a minimum set, you can do a deep research on WCS systems and what reports they show and enrich.

Reports are:

Material Flow
* Scans, No read, Unknown at each scan point, per day. Show scanners with high errors (look at history to predict errors)
* Traffic Heatmap of conveyor system

ASRS
* Storage Density in figures and %, show 90 day history and 14 day forecast 
* Heatmap of storage movements (history and forecast see above)
* Storage movements per device (shuttle, crane, etc)

Stock
* Stock per SKU in single qty, split between avaiable, allocated, unavailable

Inbound
* Expected Inbound (Received Inbound orders but not yet stock)
* Active Inbound, started vs active
* Last 90 days, values per day
* Day map of last 90 days compiled into hours of day (show peaks)

Outbound
* Expected Outbound (Received Outbound orders but not yet released)
* Active Outbound, started vs active
* Last 90 days, values per day
* Day map of last 90 days compiled into hours of day (show peaks)


---

Enrichment (research-based additions, standard commercial-WCS report set)

Material Flow (additions)
* Throughput per scan point per hour (current + history)
* Transit-time distribution induct -> arrival (p50/p95 per day) from the HU transport trace
* Recirculation rate per sorter/divert per day

Equipment (new report, fits Material Flow or its own tab)
* Device-task throughput + failure rate per equipment per day (completed vs failed)
* Equipment utilization proxy: active task time share per equipment family

GTP (new report, later phase)
* Station throughput (cycles/puts per hour), queue depth over time

Method notes
* History accumulates from deployment day (daily counters/snapshots); 90-day windows fill over time
* 14-day forecasts: weekday-seasonal moving average (simple, explainable), shown as dashed continuation
* Heatmaps reuse the existing 3D topology/twin scene (conveyor edges coloured by traffic; ASRS rack cells by movement count)
* Sidebar: Reporting is its own collapsible category; each report is a subitem (Material Flow, ASRS, Stock, Inbound, Outbound)
