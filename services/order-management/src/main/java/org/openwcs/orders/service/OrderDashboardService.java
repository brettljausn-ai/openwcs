package org.openwcs.orders.service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.sql.Date;
import java.util.Collections;
import java.util.Comparator;
import org.openwcs.orders.api.DispatchReport;
import org.openwcs.orders.api.OrderDashboardReport;
import org.openwcs.orders.api.SlaReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Live dashboard + dispatch aggregates for the operations overview. Pure SQL aggregation over
 * the existing order tables (no new tables / entities), warehouse-scoped, and best-effort:
 * every sub-figure is computed in isolation and any failure degrades to 0 / {@code null}
 * rather than failing the whole response. Days and hours are UTC, matching how the order
 * timestamps are written. Mirrors {@link OrderFlowReportService}'s style and helpers.
 *
 * @see OrderDashboardReport
 * @see DispatchReport
 */
@Service
public class OrderDashboardService {

    private static final Logger log = LoggerFactory.getLogger(OrderDashboardService.class);

    /** Open OUTBOUND statuses: created or in progress, not yet shipped or cancelled. */
    private static final List<String> OUTBOUND_OPEN_STATUSES = List.of(
            "CREATED", "RELEASED", "PARTIALLY_ALLOCATED", "ALLOCATED",
            "NOT_FULFILLABLE", "CUBING_FAILED");

    /** OUTBOUND statuses considered "ready" for dispatch (allocated or already shipped). */
    private static final List<String> OUTBOUND_READY_STATUSES = List.of("ALLOCATED", "SHIPPED");

    private final NamedParameterJdbcTemplate jdbc;

    public OrderDashboardService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OrderDashboardReport dashboard(UUID warehouseId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant startOfToday = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wh", warehouseId)
                .addValue("startOfToday", Timestamp.from(startOfToday))
                .addValue("openStatuses", OUTBOUND_OPEN_STATUSES);

        // INBOUND headline.
        long inboundOpen = safeCount(params, """
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = 'INBOUND'
                  and o.status <> 'CANCELLED'
                  and exists (select 1 from orders.order_line l
                              where l.order_id = o.order_id and l.posted_qty < l.qty)
                """);
        long inboundExpectedToday = safeCount(params, """
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = 'INBOUND'
                  and o.status <> 'CANCELLED' and o.created_at >= :startOfToday
                """);
        long inboundCompletedToday = safeCount(params, """
                select count(*) from (
                    select o.order_id
                    from orders.outbound_order o
                    join orders.order_line l on l.order_id = o.order_id
                    join orders.order_line_transaction t on t.line_id = l.line_id
                    where o.warehouse_id = :wh and o.order_type = 'INBOUND'
                      and o.status <> 'CANCELLED'
                      and not exists (select 1 from orders.order_line l2
                                      where l2.order_id = o.order_id and l2.posted_qty < l2.qty)
                    group by o.order_id
                    having max(t.posted_at) >= :startOfToday
                ) x
                """);
        // Receive errors today: inbound lines with a RECEIPT posted today whose posted quantity
        // diverged from the ordered quantity. Over-receipt (posted_qty > qty) is always an error.
        // Under-receipt (posted_qty < qty) is only an error once the line is closed short — an
        // in-progress partial receipt is expected to be topped up, not an error — so we require
        // status SHORT / CANCELLED there. This is the most defensible discrepancy signal the
        // existing order_line / order_line_transaction model offers (no dedicated error flag).
        long inboundReceiveErrorsToday = safeCount(params, """
                select count(distinct l.line_id)
                from orders.order_line l
                join orders.outbound_order o on l.order_id = o.order_id
                join orders.order_line_transaction t on t.line_id = l.line_id
                where o.warehouse_id = :wh and o.order_type = 'INBOUND'
                  and o.status <> 'CANCELLED'
                  and t.txn_type = 'RECEIPT' and t.posted_at >= :startOfToday
                  and (l.posted_qty > l.qty
                       or (l.posted_qty < l.qty and l.status in ('SHORT', 'CANCELLED')))
                """);
        String inboundProjected = projectFinish(inboundOpen, inboundCompletedToday);

        // OUTBOUND headline.
        long outboundOpen = safeCount(params, """
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                  and o.status in (:openStatuses)
                """);
        long releasedToday = safeCount(params, """
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                  and o.status <> 'CREATED' and o.status <> 'CANCELLED'
                  and o.updated_at >= :startOfToday
                """);
        long linesPickedToday = safeCount(params, """
                select count(distinct l.line_id)
                from orders.order_line l
                join orders.outbound_order o on l.order_id = o.order_id
                join orders.order_line_transaction t on t.line_id = l.line_id
                where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                  and t.txn_type = 'PICK' and t.posted_at >= :startOfToday
                """);
        long shortsToday = safeCount(params, """
                select count(*)
                from orders.order_line l
                join orders.outbound_order o on l.order_id = o.order_id
                where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                  and l.status = 'SHORT' and l.updated_at >= :startOfToday
                """);
        long shippedToday = safeCount(params, """
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                  and o.status = 'SHIPPED' and o.updated_at >= :startOfToday
                """);
        String outboundProjected = projectFinish(outboundOpen, shippedToday);

        List<OrderDashboardReport.HourSplit> perHour = perHour(params);

        log.debug("dashboard for warehouse {}: inboundOpen {}, outboundOpen {}, picked {}",
                warehouseId, inboundOpen, outboundOpen, linesPickedToday);
        return new OrderDashboardReport(
                new OrderDashboardReport.Inbound(
                        inboundOpen, inboundExpectedToday, inboundReceiveErrorsToday, inboundProjected),
                new OrderDashboardReport.Outbound(
                        outboundOpen, releasedToday, linesPickedToday, shortsToday, outboundProjected),
                perHour);
    }

    /** Today's hour-of-day split: inbound = receipts posted, outbound = picks posted. */
    private List<OrderDashboardReport.HourSplit> perHour(MapSqlParameterSource params) {
        Map<Integer, Long> inbound = new HashMap<>();
        Map<Integer, Long> outbound = new HashMap<>();
        best(() -> {
            jdbc.query("""
                    select cast(extract(hour from t.posted_at at time zone 'UTC') as int) as h,
                           o.order_type as ot, count(*) as c
                    from orders.order_line_transaction t
                    join orders.order_line l on t.line_id = l.line_id
                    join orders.outbound_order o on l.order_id = o.order_id
                    where o.warehouse_id = :wh and o.order_type in ('INBOUND', 'OUTBOUND')
                      and t.posted_at >= :startOfToday
                    group by 1, 2
                    """, params, rs -> {
                int h = rs.getInt("h");
                long c = rs.getLong("c");
                if ("INBOUND".equals(rs.getString("ot"))) {
                    inbound.put(h, c);
                } else {
                    outbound.put(h, c);
                }
            });
            return null;
        });
        List<OrderDashboardReport.HourSplit> hours = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            hours.add(new OrderDashboardReport.HourSplit(
                    hour, inbound.getOrDefault(hour, 0L), outbound.getOrDefault(hour, 0L)));
        }
        return hours;
    }

    @Transactional(readOnly = true)
    public DispatchReport dispatch(UUID warehouseId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wh", warehouseId)
                .addValue("openStatuses", OUTBOUND_OPEN_STATUSES)
                .addValue("readyStatuses", OUTBOUND_READY_STATUSES);

        List<DispatchReport.Route> routes = best(() -> {
            List<DispatchReport.Route> out = new ArrayList<>();
            jdbc.query("""
                    select o.route_code as route_code,
                           o.dispatch_by as cutoff,
                           count(*) as assigned,
                           count(*) filter (where o.status in (:readyStatuses)) as ready,
                           count(*) filter (where o.status = 'SHIPPED') as shipped
                    from orders.outbound_order o
                    where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                      and o.status in (:openStatuses)
                    group by o.route_code, o.dispatch_by
                    order by o.dispatch_by asc nulls last, o.route_code asc nulls last
                    """, params, rs -> {
                Timestamp cutoff = rs.getTimestamp("cutoff");
                long assigned = rs.getLong("assigned");
                long ready = rs.getLong("ready");
                long shipped = rs.getLong("shipped");
                String status;
                if (shipped >= assigned && assigned > 0) {
                    status = "departed";
                } else if (ready > 0) {
                    status = "loading";
                } else {
                    status = "open";
                }
                out.add(new DispatchReport.Route(
                        rs.getString("route_code"),
                        cutoff == null ? null : cutoff.toInstant().toString(),
                        assigned,
                        ready,
                        status));
            });
            return out;
        });
        if (routes == null) {
            routes = List.of();
        }

        // Next departure: the soonest still-open cut-off in the future.
        DispatchReport.NextDeparture next = null;
        Instant now = Instant.now();
        for (DispatchReport.Route route : routes) {
            if (route.cutoffIso() == null || "departed".equals(route.status())) {
                continue;
            }
            Instant cutoff = Instant.parse(route.cutoffIso());
            if (cutoff.isBefore(now)) {
                continue;
            }
            next = new DispatchReport.NextDeparture(
                    route.routeCode(), route.cutoffIso(),
                    Duration.between(now, cutoff).getSeconds());
            break; // routes are already sorted by cut-off ascending
        }

        log.debug("dispatch for warehouse {}: {} open route groups", warehouseId, routes.size());
        return new DispatchReport(next, routes);
    }

    /** One shipped outbound order's SLA inputs: created -> shipped span and cut-off compliance. */
    private record ShippedOrder(LocalDate shipDay, double cycleMinutes, Boolean onTime) {
    }

    /**
     * Service-level report over shipped OUTBOUND orders: on-time-to-cutoff percent and median
     * created -> shipped cycle time, for today and per UTC day across the window. Ship time is
     * approximated by {@code updated_at} of SHIPPED orders (no dispatch timestamp exists). The
     * median is computed in Java; best-effort, null where nothing shipped.
     */
    @Transactional(readOnly = true)
    public SlaReport sla(UUID warehouseId, int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate sinceDay = today.minusDays(Math.max(days, 1) - 1L);
        Instant since = sinceDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wh", warehouseId)
                .addValue("since", Timestamp.from(since))
                .addValue("sinceDay", Date.valueOf(sinceDay));

        List<ShippedOrder> shipped = best(() -> {
            List<ShippedOrder> out = new ArrayList<>();
            jdbc.query("""
                    select (o.updated_at at time zone 'UTC')::date as ship_day,
                           o.created_at as created_at,
                           o.updated_at as shipped_at,
                           o.dispatch_by as cutoff
                    from orders.outbound_order o
                    where o.warehouse_id = :wh and o.order_type = 'OUTBOUND'
                      and o.status = 'SHIPPED' and o.updated_at >= :since
                    """, params, rs -> {
                LocalDate shipDay = rs.getObject("ship_day", LocalDate.class);
                Instant created = rs.getTimestamp("created_at").toInstant();
                Instant shippedAt = rs.getTimestamp("shipped_at").toInstant();
                Timestamp cutoff = rs.getTimestamp("cutoff");
                double cycleMinutes = Duration.between(created, shippedAt).toMillis() / 60000.0;
                Boolean onTime = cutoff == null ? null : !shippedAt.isAfter(cutoff.toInstant());
                out.add(new ShippedOrder(shipDay, cycleMinutes, onTime));
            });
            return out;
        });
        if (shipped == null) {
            shipped = List.of();
        }

        // Group rows by ship day for the per-day buckets.
        Map<LocalDate, List<ShippedOrder>> byDay = new HashMap<>();
        for (ShippedOrder s : shipped) {
            byDay.computeIfAbsent(s.shipDay(), d -> new ArrayList<>()).add(s);
        }
        List<SlaReport.DayBucket> perDay = new ArrayList<>();
        for (LocalDate day = sinceDay; !day.isAfter(today); day = day.plusDays(1)) {
            List<ShippedOrder> rows = byDay.getOrDefault(day, List.of());
            perDay.add(new SlaReport.DayBucket(day, onTimePct(rows), cycleMedianMin(rows)));
        }

        List<ShippedOrder> todayRows = byDay.getOrDefault(today, List.of());
        log.debug("sla for warehouse {} over {} days: {} shipped outbound orders",
                warehouseId, days, shipped.size());
        return new SlaReport(onTimePct(todayRows), cycleMedianMin(todayRows), perDay);
    }

    /** Percent of orders carrying a cut-off that shipped at/before it; null when none carry one. */
    private Double onTimePct(List<ShippedOrder> rows) {
        long withCutoff = rows.stream().filter(r -> r.onTime() != null).count();
        if (withCutoff == 0) {
            return null;
        }
        long onTime = rows.stream().filter(r -> Boolean.TRUE.equals(r.onTime())).count();
        return 100.0 * onTime / withCutoff;
    }

    /** Median created -> shipped duration in minutes; null when no orders shipped. */
    private Double cycleMedianMin(List<ShippedOrder> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<Double> spans = new ArrayList<>();
        for (ShippedOrder r : rows) {
            spans.add(r.cycleMinutes());
        }
        Collections.sort(spans, Comparator.naturalOrder());
        int n = spans.size();
        if (n % 2 == 1) {
            return spans.get(n / 2);
        }
        return (spans.get(n / 2 - 1) + spans.get(n / 2)) / 2.0;
    }

    /** Simple projection: open backlog finishing at today's completion rate, ISO instant or null. */
    private String projectFinish(long open, long completedToday) {
        if (open <= 0 || completedToday <= 0) {
            return null;
        }
        Instant now = Instant.now();
        Instant startOfToday = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        double elapsedSeconds = Math.max(Duration.between(startOfToday, now).getSeconds(), 1);
        double perSecond = completedToday / elapsedSeconds;
        if (perSecond <= 0) {
            return null;
        }
        long secondsToFinish = (long) Math.ceil(open / perSecond);
        return now.plusSeconds(secondsToFinish).toString();
    }

    private long safeCount(MapSqlParameterSource params, String sql) {
        Long n = best(() -> jdbc.queryForObject(sql, params, Long.class));
        return n == null ? 0L : n;
    }

    /** Best-effort wrapper: a failing sub-query degrades to {@code null} instead of a 500. */
    private <T> T best(Supplier<T> query) {
        try {
            return query.get();
        } catch (RuntimeException e) {
            log.warn("dashboard sub-query failed, returning partial data: {}", e.getMessage());
            return null;
        }
    }
}
