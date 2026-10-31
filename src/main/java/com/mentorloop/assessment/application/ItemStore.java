package com.mentorloop.assessment.application;

import com.mentorloop.assessment.domain.AnswerKey;
import com.mentorloop.assessment.domain.ItemStatus;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ItemStore {

    void insert(Item item, AnswerKey key);

    Optional<Item> item(UUID id);

    Optional<Item> lock(UUID id);

    AnswerKey key(UUID itemId);

    Map<UUID, AnswerKey> keys(Collection<UUID> itemIds);

    List<Item> items(UUID courseId, ItemStatus status);

    List<Item> byIds(Collection<UUID> ids);

    void review(UUID id, ItemStatus status, String reviewer, Instant at);

    void setDifficulty(UUID id, double difficulty, int version, int responses, int learners, Instant at);
}
