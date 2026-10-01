package com.userlab.api;

import com.userlab.api.Dtos.*;
import com.userlab.domain.Role;
import com.userlab.domain.UserStatus;
import com.userlab.repo.UserRepository.SearchFilter;
import com.userlab.service.UserService;
import com.userlab.support.ApiErrors.PreconditionFailed;
import com.userlab.support.ApiErrors.PreconditionRequired;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/users")
public class UserController {

    public static final String MERGE_PATCH = "application/merge-patch+json";

    private final UserService users;

    public UserController(UserService users) { this.users = users; }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserWriteRequest req) {
        UserResponse u = users.create(req);
        return ResponseEntity.created(ServletUriComponentsBuilder.fromCurrentRequest()
                        .path("/{id}").buildAndExpand(u.id()).toUri())
                .eTag(etag(u.version())).body(u);
    }

    /**
     * Conditional GET. The ETag is the version in quotes, e.g. "3".
     * If the client sends If-None-Match with the current ETag, the answer is 304 with no body:
     * "your copy is still correct".
     */
    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> get(@PathVariable long id,
                                            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        UserResponse u = users.get(id);
        String etag = etag(u.version());
        if (ifNoneMatch != null && matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(CacheControl.noCache()).body(u);
    }

    /** PUT requires If-Match. Missing -> 428. Stale -> 412. */
    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> replace(@PathVariable long id,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                @Valid @RequestBody UserWriteRequest req) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequired("PUT needs an If-Match header with the ETag from your last GET, e.g. If-Match: \"3\"");
        }
        UserResponse u = users.replace(id, req, parseIfMatch(ifMatch));
        return ResponseEntity.ok().eTag(etag(u.version())).body(u);
    }

    /** PATCH only accepts application/merge-patch+json. application/json gets 415. If-Match is optional here. */
    @PatchMapping(value = "/{id}", consumes = MERGE_PATCH)
    public ResponseEntity<UserResponse> patch(@PathVariable long id,
                                              @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @RequestBody JsonNode patch) {
        Integer version = (ifMatch == null || ifMatch.isBlank()) ? null : parseIfMatch(ifMatch);
        UserResponse u = users.patch(id, patch, version);
        return ResponseEntity.ok().eTag(etag(u.version())).body(u);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        users.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Offset paging. Headers: X-Total-Count and a Link header with first/prev/next/last. */
    @GetMapping
    public ResponseEntity<PageResponse<UserSummary>> search(@RequestParam(required = false) String q,
                                                            @RequestParam(required = false) UserStatus status,
                                                            @RequestParam(required = false) Role role,
                                                            @RequestParam(required = false) String city,
                                                            @RequestParam(defaultValue = "id,asc") String sort,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size) {
        PageResponse<UserSummary> result = users.search(new SearchFilter(q, status, role, city), sort, page, size);
        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(result.totalElements()))
                .header(HttpHeaders.LINK, linkHeader(result))
                .body(result);
    }

    // ---------- helpers ----------

    static String etag(int version) { return "\"" + version + "\""; }

    private static boolean matches(String headerValue, String etag) {
        for (String part : headerValue.split(",")) {
            String t = part.trim();
            if (t.equals("*") || t.equals(etag) || t.equals("W/" + etag)) return true;
        }
        return false;
    }

    /** "3" -> 3, * -> null (any version). Anything else can never match, so it is a 412. */
    private static Integer parseIfMatch(String header) {
        String t = header.trim();
        if (t.equals("*")) return null;
        if (t.startsWith("W/")) t = t.substring(2);
        if (t.length() >= 3 && t.startsWith("\"") && t.endsWith("\"")) {
            try {
                return Integer.parseInt(t.substring(1, t.length() - 1));
            } catch (NumberFormatException ignored) { /* falls through */ }
        }
        throw new PreconditionFailed("If-Match value " + header + " is not a valid ETag. Use the ETag header from GET, quotes included.", -1);
    }

    private static String linkHeader(PageResponse<?> p) {
        List<String> links = new ArrayList<>();
        int last = Math.max(p.totalPages() - 1, 0);
        links.add(link(0, "first"));
        if (p.page() > 0) links.add(link(Math.min(p.page() - 1, last), "prev"));
        if (p.page() < last) links.add(link(p.page() + 1, "next"));
        links.add(link(last, "last"));
        return String.join(", ", links);
    }

    private static String link(int page, String rel) {
        String uri = ServletUriComponentsBuilder.fromCurrentRequest().replaceQueryParam("page", page).toUriString();
        return "<" + uri + ">; rel=\"" + rel + "\"";
    }
}
