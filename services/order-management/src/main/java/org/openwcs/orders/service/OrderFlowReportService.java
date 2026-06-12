package org.openwcs.orders.service;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.openwcs.orders.api.OrderFlowReport;
import org.openwcs.orders.domain.OrderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Order-flow aggregates for the Reporting screen (expected / active / started headline
 * figures plus per-day and hour-of-day intake histograms). Pure SQL aggregation over the
 * existing order tables, no new tables; see {@link OrderFlowReport} for the honest mapping
 * of the figures onto the order lifecycle.
 *
 * <p>Timestamp columns used: {@code outbound_order.created_at} = received,
 * {@code order_line_transaction.posted_at} (first / last per order) = started / inbound
 * completion. The lifecycle records no dispatch timestamp, so OUTBOUND completion is
 * approximated by {@code updated_at} of SHIPPED orders (shipping is their final transition).
 * Days and hours are UTC, matching how the timestamps are written.
 */
@Service
public class OrderFlowReportService {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowReportService.class);

    /** OUTBOUND in-progress statuses: released into the automated area, not yet shipped. */
    private static final List<String> OUTBOUND_ACTIVE_STATUSES = List.of(
            "RELEASED", "PARTIALLY_ALLOCATED", "ALLOCATED", "NOT_FULFILLABLE", "CUBING_FAILED");

    /** An order with at least one posted line transaction (work has physically begun). */
    private static final String HAS_TRANSACTION = """
            exists (select 1 from orders.order_line_transaction t
                    join orders.order_line l on t.line_id = l.line_id
                    where l.order_id = o.order_id)""";

    /** An order with some line not yet fully posted (inbound: stock still missing). */
    private static final String HAS_OPEN_LINE = """
            exists (select 1 from orders.order_line l
                    where l.order_id = o.order_id and l.posted_qty < l.qty)""";

    private final NamedParameterJdbcTemplate jdbc;

    public OrderFlowReportService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public OrderFlowReport flow(UUID warehouseId, OrderType direction, int days) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate sinceDay = today.minusDays(Math.max(days, 1) - 1L);
        Instant since = sinceDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("wh", warehouseId)
                .addValue("type", direction.name())
                .addValue("since", Timestamp.from(since))
                .addValue("sinceDay", Date.valueOf(sinceDay))
                .addValue("activeStatuses", OUTBOUND_ACTIVE_STATUSES);

        long expected;
        long active;
        long started;
        Map<LocalDate, Long> completedPerDay;
        if (direction == OrderType.INBOUND) {
            expected = count(params, "o.status <> 'CANCELLED' and not " + HAS_TRANSACTION);
            active = count(params, "o.status <> 'CANCELLED' and " + HAS_OPEN_LINE);
            started = count(params,
                    "o.status <> 'CANCELLED' and " + HAS_OPEN_LINE + " and " + HAS_TRANSACTION);
            completedPerDay = inboundCompletedPerDay(params);
        } else {
            expected = count(params, "o.status = 'CREATED'");
            active = count(params, "o.status in (:activeStatuses)");
            started = count(params, "o.status in (:activeStatuses) and " + HAS_TRANSACTION);
            completedPerDay = outboundCompletedPerDay(params);
        }

        Map<LocalDate, Long> receivedPerDay = perDay(params, """
                select (o.created_at at time zone 'UTC')::date as day, count(*) as c
                from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = :type and o.created_at >= :since
                group by 1
                """);
        Map<LocalDate, Long> startedPerDay = perDay(params, """
                select day, count(*) as c from (
                    select (min(t.posted_at) at time zone 'UTC')::date as day
                    from orders.order_line_transaction t
                    join orders.order_line l on t.line_id = l.line_id
                    join orders.outbound_order o on l.order_id = o.order_id
                    where o.warehouse_id = :wh and o.order_type = :type
                    group by o.order_id
                ) x where day >= :sinceDay
                group by day
                """);

        List<OrderFlowReport.DayBucket> dayBuckets = new ArrayList<>();
        for (LocalDate day = sinceDay; !day.isAfter(today); day = day.plusDays(1)) {
            dayBuckets.add(new OrderFlowReport.DayBucket(
                    day,
                    receivedPerDay.getOrDefault(day, 0L),
                    startedPerDay.getOrDefault(day, 0L),
                    completedPerDay.getOrDefault(day, 0L)));
        }

        Map<Integer, Long> byHour = new HashMap<>();
        jdbc.query("""
                select cast(extract(hour from o.created_at at time zone 'UTC') as int) as h, count(*) as c
                from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = :type and o.created_at >= :since
                group by 1
                """, params, rs -> {
            byHour.put(rs.getInt("h"), rs.getLong("c"));
        });
        List<OrderFlowReport.HourBucket> hourBuckets = new ArrayList<>();
        for (int hour = 0; hour < 24; hour++) {
            hourBuckets.add(new OrderFlowReport.HourBucket(hour, byHour.getOrDefault(hour, 0L)));
        }

        log.debug("order-flow report for warehouse {} {} over {} days: expected {}, active {},"
                + " started {}", warehouseId, direction, days, expected, active, started);
        return new OrderFlowReport(expected, active, started, dayBuckets, hourBuckets);
    }

    /** Orders of the requested warehouse + direction matching the extra condition. */
    private long count(MapSqlParameterSource params, String condition) {
        Long n = jdbc.queryForObject("""
                select count(*) from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = :type
                """ + " and " + condition,
                params, Long.class);
        return n == null ? 0L : n;
    }

    private Map<LocalDate, Long> perDay(MapSqlParameterSource params, String sql) {
        Map<LocalDate, Long> result = new HashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getObject("day", LocalDate.class), rs.getLong("c"));
        });
        return result;
    }

    /** INBOUND completion: every line fully posted; bucketed by the last receipt's day. */
    private Map<LocalDate, Long> inboundCompletedPerDay(MapSqlParameterSource params) {
        return perDay(params, """
                select day, count(*) as c from (
                    select (max(t.posted_at) at time zone 'UTC')::date as day
                    from orders.order_line_transaction t
                    join orders.order_line l on t.line_id = l.line_id
                    join orders.outbound_order o on l.order_id = o.order_id
                    where o.warehouse_id = :wh and o.order_type = :type
                      and o.status <> 'CANCELLED'
                      and not exists (select 1 from orders.order_line l2
                                      where l2.order_id = o.order_id and l2.posted_qty < l2.qty)
                    group by o.order_id
                ) x where day >= :sinceDay
                group by day
                """);
    }

    /**
     * OUTBOUND completion: SHIPPED orders by the day of their last update. The lifecycle keeps
     * no dispatch timestamp; shipping is the final transition, so updated_at is an honest
     * approximation of when the order shipped.
     */
    private Map<LocalDate, Long> outboundCompletedPerDay(MapSqlParameterSource params) {
        return perDay(params, """
                select (o.updated_at at time zone 'UTC')::date as day, count(*) as c
                from orders.outbound_order o
                where o.warehouse_id = :wh and o.order_type = :type
                  and o.status = 'SHIPPED' and o.updated_at >= :since
                group by 1
                """);
    }
}
