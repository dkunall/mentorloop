package com.mentorloop.assessment.application;

import com.mentorloop.assessment.domain.ItemStatus;
import com.mentorloop.assessment.domain.ItemType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An item as stored, without its answer key. */
public record Item(UUID id, UUID courseId, List<UUID> conceptIds, ItemType type, String stem, List<String> options,
                   String explanation, ItemStatus status, String origin, UUID sourceLesson, double difficulty,
                   int difficultyVersion, String createdBy, Instant createdAt, String reviewedBy) {
}
