package dev.era.accountability.domain;

import java.time.LocalDate;

public record StreakInfo(long commitmentId, int currentLen, int bestLen, LocalDate lastSuccessOn) {}
