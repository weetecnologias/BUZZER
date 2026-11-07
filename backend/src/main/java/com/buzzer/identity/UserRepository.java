package com.buzzer.identity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
public class UserRepository {
    public record User(UUID id, String phone, String fullName, String email, String preferredLanguage, String status) {}

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<User> findByPhone(String phone) {
        return jdbc.sql("SELECT id, phone, full_name, email, preferred_language, status FROM users WHERE phone = :p")
                .param("p", phone).query(User.class).optional();
    }

    public Optional<User> findById(UUID id) {
        return jdbc.sql("SELECT id, phone, full_name, email, preferred_language, status FROM users WHERE id = :id")
                .param("id", id).query(User.class).optional();
    }

    public User create(String phone, String fullName) {
        UUID id = jdbc.sql("INSERT INTO users(phone, full_name) VALUES (:p, :n) RETURNING id")
                .param("p", phone).param("n", fullName).query(UUID.class).single();
        return findById(id).orElseThrow();
    }

    public void updateProfile(UUID id, String fullName, String email, String preferredLanguage) {
        jdbc.sql("""
                UPDATE users SET full_name = COALESCE(:n, full_name), email = COALESCE(:e, email),
                  preferred_language = COALESCE(:l, preferred_language), updated_at = now() WHERE id = :id""")
                .param("n", fullName).param("e", email).param("l", preferredLanguage).param("id", id).update();
    }

    public Set<Role> roles(UUID userId) {
        List<String> names = jdbc.sql("SELECT role FROM user_roles WHERE user_id = :u").param("u", userId).query(String.class).list();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        names.forEach(n -> roles.add(Role.valueOf(n)));
        return roles;
    }

    public void grantRole(UUID userId, Role role, UUID grantedBy) {
        jdbc.sql("INSERT INTO user_roles(user_id, role, granted_by) VALUES (:u, :r, :g) ON CONFLICT DO NOTHING")
                .param("u", userId).param("r", role.name()).param("g", grantedBy).update();
    }

    public void revokeRole(UUID userId, Role role) {
        jdbc.sql("DELETE FROM user_roles WHERE user_id = :u AND role = :r").param("u", userId).param("r", role.name()).update();
    }

    public void setStatus(UUID userId, String status) {
        jdbc.sql("UPDATE users SET status = :s, updated_at = now() WHERE id = :id").param("s", status).param("id", userId).update();
    }
}
