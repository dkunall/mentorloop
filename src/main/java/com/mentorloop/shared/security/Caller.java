package com.mentorloop.shared.security;

import com.mentorloop.shared.error.ApiException;

/** A JWT caller. Passed explicitly into application services so authorisation is visible in the code. */
public record Caller(String subject, Role role) {

    public static Caller system(String job) {
        return new Caller("system:" + job, Role.ADMIN);
    }

    public boolean isStaff() {
        return role == Role.INSTRUCTOR || role == Role.ADMIN;
    }

    public void requireStaff() {
        if (!isStaff()) {
            throw ApiException.forbidden("forbidden", "This needs the INSTRUCTOR role");
        }
    }

    /** Instructors manage the courses they own; admins manage all of them. */
    public void requireOwnerOf(String courseOwner) {
        requireStaff();
        if (role != Role.ADMIN && !subject.equals(courseOwner)) {
            throw ApiException.forbidden("not-course-owner", "Only the course's instructor can do this");
        }
    }
}
