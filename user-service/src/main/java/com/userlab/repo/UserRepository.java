package com.userlab.repo;

import com.userlab.api.Dtos.*;
import com.userlab.domain.AddressType;
import com.userlab.domain.Role;
import com.userlab.domain.UserStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Array;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Plain SQL through JdbcClient. What you read here is exactly what PostgreSQL
 * runs.
 */
@Repository
public class UserRepository {

        /** One row of the users table, before addresses and roles are attached. */
        public record UserRow(long id, String userCode, String username, String email, UserStatus status, int version,
                        ProfileDto profile, PreferencesDto preferences, AuditDto audit) {
        }

        /**
         * Sort keys the API accepts, mapped to SQL. Column names can't be bind
         * parameters, so we whitelist.
         */
        public static final Map<String, String> SORT_COLUMNS = Map.of(
                        "id", "u.id",
                        "username", "u.username",
                        "email", "lower(u.email)",
                        "lastName", "u.last_name",
                        "createdAt", "u.created_at",
                        "status", "u.status");

        private final JdbcClient jdbc;
        private final JsonMapper json;

        public UserRepository(JdbcClient jdbc, JsonMapper json) {
                this.jdbc = jdbc;
                this.json = json;
        }

        public long insert(UserWriteRequest r) {
                return jdbc.sql("""
                                INSERT INTO users (username, email, first_name, last_name, date_of_birth, phone, preferences)
                                VALUES (:username, :email, :firstName, :lastName, :dob, :phone, CAST(:prefs AS jsonb))
                                RETURNING id""")
                                .param("username", r.username()).param("email", r.email())
                                .param("firstName", r.profile().firstName()).param("lastName", r.profile().lastName())
                                .param("dob", r.profile().dateOfBirth()).param("phone", r.profile().phone())
                                .param("prefs", json.writeValueAsString(r.preferences()))
                                .query(Long.class).single();
        }

        /**
         * Optimistic locking in ONE statement: "update only if the version is still
         * what I read".
         * If someone else updated first, the WHERE matches 0 rows. Checking the version
         * in Java first
         * and then updating would leave a gap where another request can slip in.
         * expectedVersion == null means "do not check" (used by the ignoreIfMatch bug
         * switch and If-Match: *).
         */
        public int update(long id, UserWriteRequest r, UserStatus status, Integer expectedVersion) {
                return jdbc.sql("""
                                UPDATE users SET username = :username, email = :email, first_name = :firstName,
                                       last_name = :lastName, date_of_birth = :dob, phone = :phone,
                                       preferences = CAST(:prefs AS jsonb), status = :status, version = version + 1
                                WHERE id = :id AND status <> 'DELETED'
                                  AND (CAST(:expected AS integer) IS NULL OR version = :expected)""")
                                .param("id", id).param("username", r.username()).param("email", r.email())
                                .param("firstName", r.profile().firstName()).param("lastName", r.profile().lastName())
                                .param("dob", r.profile().dateOfBirth()).param("phone", r.profile().phone())
                                .param("prefs", json.writeValueAsString(r.preferences()))
                                .param("status", status.name()).param("expected", expectedVersion)
                                .update();
        }

        public int softDelete(long id) {
                return jdbc.sql("""
                                UPDATE users SET status = 'DELETED', deleted_at = now(), version = version + 1
                                WHERE id = :id AND status <> 'DELETED'""")
                                .param("id", id).update();
        }

        public void replaceAddresses(long userId, List<AddressRequest> addresses) {
                jdbc.sql("DELETE FROM addresses WHERE user_id = :id").param("id", userId).update();
                for (AddressRequest a : addresses) {
                        jdbc.sql("""
                                        INSERT INTO addresses (user_id, type, is_primary, line1, line2, city, state, pincode, lat, lon)
                                        VALUES (:userId, :type, :primary, :line1, :line2, :city, :state, :pincode, :lat, :lon)""")
                                        .param("userId", userId).param("type", a.type().name())
                                        .param("primary", a.isPrimary())
                                        .param("line1", a.line1()).param("line2", a.line2()).param("city", a.city())
                                        .param("state", a.state()).param("pincode", a.pincode())
                                        .param("lat", a.geo() == null ? null : a.geo().lat())
                                        .param("lon", a.geo() == null ? null : a.geo().lon())
                                        .update();
                }
        }

        /**
         * Upsert: a duplicate role in the request is ignored by ON CONFLICT instead of
         * failing on the primary key.
         */
        public void replaceRoles(long userId, List<Role> roles) {
                jdbc.sql("DELETE FROM user_roles WHERE user_id = :id").param("id", userId).update();
                for (Role role : roles) {
                        jdbc.sql("""
                                        INSERT INTO user_roles (user_id, role_code) VALUES (:userId, :role)
                                        ON CONFLICT (user_id, role_code) DO NOTHING""")
                                        .param("userId", userId).param("role", role.name()).update();
                }
        }

        public Optional<UserRow> findRow(long id) {
                return jdbc.sql("""
                                SELECT id, user_code, username, email, status, version, first_name, last_name, date_of_birth,
                                       phone, preferences::text AS preferences, created_at, updated_at, last_login_at, deleted_at
                                FROM users WHERE id = :id""")
                                .param("id", id)
                                .query((rs, n) -> new UserRow(
                                                rs.getLong("id"), rs.getString("user_code"), rs.getString("username"),
                                                rs.getString("email"),
                                                UserStatus.valueOf(rs.getString("status")), rs.getInt("version"),
                                                new ProfileDto(rs.getString("first_name"), rs.getString("last_name"),
                                                                rs.getObject("date_of_birth", LocalDate.class),
                                                                rs.getString("phone")),
                                                json.readValue(rs.getString("preferences"), PreferencesDto.class),
                                                new AuditDto(rs.getObject("created_at", OffsetDateTime.class),
                                                                rs.getObject("updated_at", OffsetDateTime.class),
                                                                rs.getObject("last_login_at", OffsetDateTime.class),
                                                                rs.getObject("deleted_at", OffsetDateTime.class))))
                                .optional();
        }

        public List<AddressResponse> findAddresses(long userId) {
                return jdbc.sql("""
                                SELECT id, type, is_primary, line1, line2, city, state, pincode, lat, lon
                                FROM addresses WHERE user_id = :id ORDER BY is_primary DESC, id""")
                                .param("id", userId)
                                .query((rs, n) -> new AddressResponse(rs.getLong("id"),
                                                AddressType.valueOf(rs.getString("type")),
                                                rs.getBoolean("is_primary"), rs.getString("line1"),
                                                rs.getString("line2"),
                                                rs.getString("city"), rs.getString("state"), rs.getString("pincode"),
                                                rs.getBigDecimal("lat") == null ? null
                                                                : new GeoDto(rs.getBigDecimal("lat"),
                                                                                rs.getBigDecimal("lon"))))
                                .list();
        }

        public List<Role> findRoles(long userId) {
                return jdbc.sql("SELECT role_code FROM user_roles WHERE user_id = :id ORDER BY role_code")
                                .param("id", userId)
                                .query((rs, n) -> Role.valueOf(rs.getString("role_code"))).list();
        }

        public record SearchFilter(String q, UserStatus status, Role role, String city) {
        }

        /**
         * Search with LEFT JOINs, array_agg and GROUP BY.
         * - LEFT JOIN on the primary address: users without an address still appear
         * (primaryCity = null).
         * - array_agg(...) FILTER (...): collects roles into a text[] per user.
         * - By default DELETED users are hidden. Ask for status=DELETED to see them.
         * - q is matched with ILIKE; % and _ in the user's text are escaped so they
         * match literally.
         */
        public List<UserSummary> search(SearchFilter f, String sortColumn, String direction, int page, int size) {
                String sql = """
                                SELECT u.id, u.user_code, u.username, u.email, u.first_name, u.last_name, u.status, u.created_at,
                                       pa.city AS primary_city,
                                       COALESCE(array_agg(ur.role_code ORDER BY ur.role_code)
                                                FILTER (WHERE ur.role_code IS NOT NULL), '{}') AS roles
                                FROM users u
                                LEFT JOIN addresses pa ON pa.user_id = u.id AND pa.is_primary
                                LEFT JOIN user_roles ur ON ur.user_id = u.id
                                %s
                                GROUP BY u.id, pa.city
                                ORDER BY %s %s, u.id %s
                                LIMIT :size OFFSET :offset"""
                                .formatted(WHERE, sortColumn, direction, direction);
                return bindFilter(jdbc.sql(sql), f)
                                .param("size", size).param("offset", (long) page * size)
                                .query((rs, n) -> {
                                        Array arr = rs.getArray("roles");
                                        List<Role> roles = Arrays.stream((String[]) arr.getArray()).map(Role::valueOf)
                                                        .toList();
                                        return new UserSummary(rs.getLong("id"), rs.getString("user_code"),
                                                        rs.getString("username"),
                                                        rs.getString("email"),
                                                        rs.getString("first_name") + " " + rs.getString("last_name"),
                                                        UserStatus.valueOf(rs.getString("status")), roles,
                                                        rs.getString("primary_city"),
                                                        rs.getObject("created_at", OffsetDateTime.class));
                                })
                                .list();
        }

        public long count(SearchFilter f) {
                String sql = """
                                SELECT count(*) FROM users u
                                LEFT JOIN addresses pa ON pa.user_id = u.id AND pa.is_primary
                                %s""".formatted(WHERE);
                return bindFilter(jdbc.sql(sql), f).query(Long.class).single();
        }

        private static final String WHERE = """
                        WHERE ((CAST(:status AS varchar) IS NULL AND u.status <> 'DELETED') OR u.status = :status)
                          AND (CAST(:q AS varchar) IS NULL
                               OR u.username ILIKE :pattern ESCAPE '\\'
                               OR u.email ILIKE :pattern ESCAPE '\\'
                               OR (u.first_name || ' ' || u.last_name) ILIKE :pattern ESCAPE '\\')
                          AND (CAST(:role AS varchar) IS NULL
                               OR EXISTS (SELECT 1 FROM user_roles r2 WHERE r2.user_id = u.id AND r2.role_code = :role))
                          AND (CAST(:city AS varchar) IS NULL OR lower(pa.city) = lower(:city))""";

        private static JdbcClient.StatementSpec bindFilter(JdbcClient.StatementSpec spec, SearchFilter f) {
                String q = (f.q() == null || f.q().isBlank()) ? null : f.q().trim();
                String pattern = q == null ? null
                                : "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                return spec.param("status", f.status() == null ? null : f.status().name())
                                .param("q", q).param("pattern", pattern)
                                .param("role", f.role() == null ? null : f.role().name())
                                .param("city", (f.city() == null || f.city().isBlank()) ? null : f.city().trim());
        }
}
