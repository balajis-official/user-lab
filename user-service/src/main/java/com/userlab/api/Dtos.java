package com.userlab.api;

import com.userlab.domain.AddressType;
import com.userlab.domain.Channel;
import com.userlab.domain.Role;
import com.userlab.domain.UserStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;


import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** Every JSON shape of the API in one file. */
public final class Dtos {
    private Dtos() {}

    public record ProfileDto(
            @NotBlank @Size(max = 60) String firstName,
            @NotBlank @Size(max = 60) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @Pattern(regexp = "^[6-9]\\d{9}$", message = "must be a 10-digit Indian mobile number") String phone) {}

    public record GeoDto(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal lat,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal lon) {}

    public record AddressRequest(
            @NotNull AddressType type,
            Boolean primary,
            @NotBlank @Size(max = 120) String line1,
            @Size(max = 120) String line2,
            @NotBlank @Size(max = 60) String city,
            @NotBlank @Size(max = 60) String state,
            @NotNull @Pattern(regexp = "^\\d{6}$", message = "must be exactly 6 digits") String pincode,
            @Valid GeoDto geo) {
        public boolean isPrimary() { return Boolean.TRUE.equals(primary); }
    }

    public record NotificationsDto(
            @NotNull Boolean email,
            @NotNull Boolean sms,
            @NotNull @Size(max = 3) List<@NotNull Channel> channels) {}

    public record PreferencesDto(
            @NotNull @Pattern(regexp = "^(en|ta|hi|ml|kn|te)$", message = "must be one of en, ta, hi, ml, kn, te") String language,
            @NotBlank String timezone,
            @NotNull @Valid NotificationsDto notifications) {}

    /** Body of POST and PUT. PUT replaces everything, including the addresses and roles lists. */
    public record UserWriteRequest(
            @NotNull @Pattern(regexp = "^[a-z][a-z0-9._]{2,29}$",
                    message = "3-30 chars: lowercase letters, digits, dot, underscore; must start with a letter") String username,
            @NotNull @Email @Size(max = 120) String email,
            @NotNull @Valid ProfileDto profile,
            @NotNull @Valid PreferencesDto preferences,
            @NotNull @Size(max = 5) List<@Valid @NotNull AddressRequest> addresses,
            @NotNull @Size(min = 1, message = "a user needs at least one role") List<@NotNull Role> roles) {}

    /**
     * The document a JSON Merge Patch (RFC 7396) is applied to: the write shape plus status.
     * The service builds it from the current user, merges the patch into it, then validates the result.
     */
    public record UserPatchDocument(
            @NotNull @Pattern(regexp = "^[a-z][a-z0-9._]{2,29}$",
                    message = "3-30 chars: lowercase letters, digits, dot, underscore; must start with a letter") String username,
            @NotNull @Email @Size(max = 120) String email,
            @NotNull @Valid ProfileDto profile,
            @NotNull @Valid PreferencesDto preferences,
            @NotNull @Size(max = 5) List<@Valid @NotNull AddressRequest> addresses,
            @NotNull @Size(min = 1, message = "a user needs at least one role") List<@NotNull Role> roles,
            @NotNull UserStatus status) {
        public UserWriteRequest toWrite() {
            return new UserWriteRequest(username, email, profile, preferences, addresses, roles);
        }
    }

    // ---------- responses ----------

    public record AddressResponse(long id, AddressType type, boolean primary, String line1, String line2,
                                  String city, String state, String pincode, GeoDto geo) {}

    public record AuditDto(OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           OffsetDateTime lastLoginAt, OffsetDateTime deletedAt) {}

    public record UserResponse(long id, String userCode, String username, String email, UserStatus status,
                               int version, ProfileDto profile, List<AddressResponse> addresses, List<Role> roles,
                               PreferencesDto preferences, AuditDto audit) {}

    public record UserSummary(long id, String userCode, String username, String email, String fullName,
                              UserStatus status, List<Role> roles, String primaryCity, OffsetDateTime createdAt) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
}
