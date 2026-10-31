package com.mentorloop.shared.security;

/**
 * LEARNER is everyone with a valid token. INSTRUCTOR authors courses, approves generated items and grades by hand.
 * ADMIN can do what instructors do on every course. Instructor and admin subjects come from configuration.
 */
public enum Role {
    LEARNER,
    INSTRUCTOR,
    ADMIN
}
