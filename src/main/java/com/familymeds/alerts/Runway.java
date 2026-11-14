package com.familymeds.alerts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * How long the stock lasts (DESIGN §4 "Runway", D11, R5). Pure calculation, no database.
 * <ul>
 *   <li>For a medicine with a daily dose: {@code estimated = max(0, usable − dose × whole days since the last
 *       stock entry)}. Each entry (dose, purchase, recount) restarts the estimate from the recorded stock, so a
 *       dose logged every day keeps the estimate equal to the recorded stock. The estimate is never written
 *       to the ledger.</li>
 *   <li>{@code daysLeft = floor(estimated / dose)}; LOW when {@code daysLeft ≤ warnDays}; OUT when nothing is
 *       left.</li>
 *   <li>Without a daily dose: LOW when usable ≤ min threshold (if a threshold is set), OUT when usable is 0.</li>
 * </ul>
 */
public record Runway(BigDecimal recorded, BigDecimal estimated, Integer daysLeft, LocalDate runOutDate,
        Status status) {

    public enum Status { OK, LOW, OUT }

    /**
     * @param usable           usable (unexpired) stock now
     * @param lastEntryDate    date of the latest stock entry in the household's timezone; null = no entries yet
     */
    public static Runway of(BigDecimal usable, BigDecimal dailyDose, int warnDays, BigDecimal minThreshold,
            LocalDate lastEntryDate, LocalDate today) {
        if (dailyDose != null && dailyDose.signum() > 0) {
            long days = lastEntryDate == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(lastEntryDate, today));
            BigDecimal estimated = usable.subtract(dailyDose.multiply(BigDecimal.valueOf(days))).max(BigDecimal.ZERO);
            int daysLeft = estimated.divide(dailyDose, 0, RoundingMode.FLOOR).intValueExact();
            Status status = estimated.signum() == 0 ? Status.OUT : daysLeft <= warnDays ? Status.LOW : Status.OK;
            return new Runway(usable, estimated, daysLeft, today.plusDays(daysLeft), status);
        }
        Status status = usable.signum() == 0 ? Status.OUT
                : minThreshold != null && minThreshold.signum() > 0 && usable.compareTo(minThreshold) <= 0
                        ? Status.LOW : Status.OK;
        return new Runway(usable, usable, null, null, status);
    }
}
