package com.userlab.service;

import com.userlab.api.Dtos.*;
import com.userlab.domain.Channel;
import com.userlab.domain.UserStatus;
import com.userlab.repo.UserRepository;
import com.userlab.repo.UserRepository.SearchFilter;
import com.userlab.repo.UserRepository.UserRow;
import com.userlab.support.ApiErrors.*;
import com.userlab.support.AppClock;
import com.userlab.support.BugSwitches;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.*;

@Service
public class UserService {

    /** Allowed status changes through PATCH. DELETED is reachable only through DELETE. */
    private static final Map<UserStatus, Set<UserStatus>> TRANSITIONS = Map.of(
            UserStatus.PENDING_VERIFICATION, Set.of(UserStatus.ACTIVE),
            UserStatus.ACTIVE, Set.of(UserStatus.SUSPENDED),
            UserStatus.SUSPENDED, Set.of(UserStatus.ACTIVE),
            UserStatus.DELETED, Set.of());

    private final UserRepository repo;
    private final AppClock clock;
    private final BugSwitches bugs;
    private final Validator validator;
    private final JsonMapper json;

    public UserService(UserRepository repo, AppClock clock, BugSwitches bugs, Validator validator, JsonMapper json) {
        this.repo = repo;
        this.clock = clock;
        this.bugs = bugs;
        this.validator = validator;
        this.json = json;
    }

    @Transactional
    public UserResponse create(UserWriteRequest req) {
        checkRules(req);
        long id = repo.insert(req);
        repo.replaceAddresses(id, req.addresses());
        repo.replaceRoles(id, req.roles());
        return load(id);
    }

    /** 404 if it never existed, 410 if it was deleted. */
    public UserResponse get(long id) {
        UserResponse user = load(id);
        if (user.status() == UserStatus.DELETED) {
            throw new Gone("User " + id + " was deleted");
        }
        return user;
    }

    /**
     * PUT. ifMatchVersion: the version from the If-Match header, or null for "If-Match: *".
     * The controller already rejected a missing header with 428.
     */
    @Transactional
    public UserResponse replace(long id, UserWriteRequest req, Integer ifMatchVersion) {
        UserRow current = requireLive(id);
        checkRules(req);
        writeWithVersionCheck(id, req, current.status(), ifMatchVersion);
        return load(id);
    }

    /**
     * PATCH with application/merge-patch+json.
     * 1. Build the current document  2. merge the patch into it  3. validate the RESULT
     * 4. check rules and the status change  5. write, guarded by the version we read in step 1.
     * Validating the result (not the patch) is why "profile": {"firstName": null} gives 400:
     * after the merge, firstName is missing, and firstName is required.
     */
    @Transactional
    public UserResponse patch(long id, JsonNode patch, Integer ifMatchVersion) {
        if (!patch.isObject()) {
            throw validation("$", patch.toString(), "a merge patch for a user must be a JSON object");
        }
        UserRow current = requireLive(id);
        if (ifMatchVersion != null && ifMatchVersion != current.version() && !bugs.ignoreIfMatch()) {
            throw new PreconditionFailed("If-Match version " + ifMatchVersion + " does not match current version "
                    + current.version(), current.version());
        }
        UserResponse loaded = load(id);
        UserPatchDocument before = new UserPatchDocument(loaded.username(), loaded.email(), loaded.profile(),
                loaded.preferences(), toRequests(loaded.addresses()), loaded.roles(), loaded.status());

        JsonNode merged = MergePatch.apply(json.valueToTree(before), patch, bugs.mergePatchNullIgnored());
        UserPatchDocument after;
        try {
            after = json.treeToValue(merged, UserPatchDocument.class);
        } catch (JacksonException e) {
            throw validation("$", null, "patched document is not valid: " + e.getOriginalMessage());
        }
        Set<ConstraintViolation<UserPatchDocument>> violations = validator.validate(after);
        if (!violations.isEmpty()) {
            throw new ValidationFailed(violations.stream()
                    .sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
                    .map(v -> error(v.getPropertyPath().toString(), v.getMessage(), v.getInvalidValue()))
                    .toList());
        }
        checkRules(after.toWrite());
        checkTransition(current.status(), after.status());
        // Guard with the version we READ, even without If-Match: a change made by someone else
        // between our read and our write must not be silently overwritten.
        writeWithVersionCheck(id, after.toWrite(), after.status(), current.version());
        return load(id);
    }

    /** Soft delete. Idempotent: deleting an already-deleted user is still 204. */
    @Transactional
    public void delete(long id) {
        repo.findRow(id).orElseThrow(() -> new NotFound("User " + id + " not found"));
        repo.softDelete(id);
    }

    public PageResponse<UserSummary> search(SearchFilter filter, String sort, int page, int size) {
        if (page < 0) throw validation("page", page, "must be 0 or more");
        if (size < 1 || size > 100) throw validation("size", size, "must be between 1 and 100");
        String[] parts = sort.split(",");
        String column = UserRepository.SORT_COLUMNS.get(parts[0]);
        String direction = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "asc";
        if (column == null || parts.length > 2 || !(direction.equals("asc") || direction.equals("desc"))) {
            throw validation("sort", sort, "use field[,asc|desc] where field is one of "
                    + new TreeSet<>(UserRepository.SORT_COLUMNS.keySet()));
        }
        List<UserSummary> content = repo.search(filter, column, direction, page, size);
        long total = repo.count(filter);
        return new PageResponse<>(content, page, size, total, (int) ((total + size - 1) / size));
    }

    // ---------- helpers ----------

    private void writeWithVersionCheck(long id, UserWriteRequest req, UserStatus status, Integer expectedVersion) {
        Integer guard = bugs.ignoreIfMatch() ? null : expectedVersion;
        if (repo.update(id, req, status, guard) == 0) {
            UserRow now = requireLive(id);
            throw new PreconditionFailed("Version " + expectedVersion + " is stale. Current version is "
                    + now.version() + ". GET the user again and retry with the new ETag.", now.version());
        }
        repo.replaceAddresses(id, req.addresses());
        repo.replaceRoles(id, req.roles());
    }

    private UserRow requireLive(long id) {
        UserRow row = repo.findRow(id).orElseThrow(() -> new NotFound("User " + id + " not found"));
        if (row.status() == UserStatus.DELETED) throw new Gone("User " + id + " was deleted");
        return row;
    }

    private UserResponse load(long id) {
        UserRow r = repo.findRow(id).orElseThrow(() -> new NotFound("User " + id + " not found"));
        return new UserResponse(r.id(), r.userCode(), r.username(), r.email(), r.status(), r.version(),
                r.profile(), repo.findAddresses(id), repo.findRoles(id), r.preferences(), r.audit());
    }

    /** Rules that need several fields together -> 422. */
    private void checkRules(UserWriteRequest req) {
        try {
            ZoneId.of(req.preferences().timezone());
        } catch (DateTimeException e) {
            throw validation("preferences.timezone", req.preferences().timezone(), "unknown time zone");
        }
        if (req.profile().dateOfBirth().isAfter(clock.today().minusYears(18))) {
            throw new BusinessRule("UNDERAGE", "profile.dateOfBirth",
                    "User must be at least 18 on " + clock.today());
        }
        long primaries = req.addresses().stream().filter(AddressRequest::isPrimary).count();
        if (primaries > 1) {
            throw new BusinessRule("MULTIPLE_PRIMARY_ADDRESSES", "addresses", primaries + " addresses are marked primary; only one is allowed");
        }
        if (!req.addresses().isEmpty() && primaries == 0) {
            throw new BusinessRule("PRIMARY_ADDRESS_REQUIRED", "addresses", "One address must be marked primary");
        }
        NotificationsDto n = req.preferences().notifications();
        if (Boolean.TRUE.equals(n.sms()) && req.profile().phone() == null) {
            throw new BusinessRule("SMS_NEEDS_PHONE", "preferences.notifications.sms", "SMS notifications need profile.phone");
        }
        if (n.channels().contains(Channel.SMS) && !Boolean.TRUE.equals(n.sms())) {
            throw new BusinessRule("SMS_CHANNEL_DISABLED", "preferences.notifications.channels", "Channel SMS is listed but sms is false");
        }
    }

    private void checkTransition(UserStatus from, UserStatus to) {
        if (from == to) return;
        if (!TRANSITIONS.get(from).contains(to)) {
            throw new Conflict("INVALID_STATUS_TRANSITION", "Cannot change status from " + from + " to " + to
                    + (to == UserStatus.DELETED ? ". Use DELETE instead." : ""));
        }
    }

    private static List<AddressRequest> toRequests(List<AddressResponse> list) {
        return list.stream().map(a -> new AddressRequest(a.type(), a.primary(), a.line1(), a.line2(),
                a.city(), a.state(), a.pincode(), a.geo())).toList();
    }

    private static ValidationFailed validation(String field, Object rejected, String message) {
        return new ValidationFailed(List.of(error(field, message, rejected)));
    }

    private static Map<String, Object> error(String field, String message, Object rejected) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", field);
        m.put("message", message);
        m.put("rejectedValue", rejected);
        return m;
    }
}
