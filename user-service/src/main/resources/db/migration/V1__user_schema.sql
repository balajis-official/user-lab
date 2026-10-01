-- Reference data: the roles a user can hold.
CREATE TABLE roles (
    code         VARCHAR(20)  PRIMARY KEY,
    description  VARCHAR(100) NOT NULL
);

CREATE TABLE users (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_code      VARCHAR(12)  GENERATED ALWAYS AS ('USR-' || lpad(id::text, 6, '0')) STORED,
    username       VARCHAR(30)  NOT NULL,
    email          VARCHAR(120) NOT NULL,
    first_name     VARCHAR(60)  NOT NULL,
    last_name      VARCHAR(60)  NOT NULL,
    date_of_birth  DATE         NOT NULL,
    phone          VARCHAR(10),
    status         VARCHAR(25)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    preferences    JSONB        NOT NULL DEFAULT '{}'::jsonb,
    version        INTEGER      NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_login_at  TIMESTAMPTZ,
    deleted_at     TIMESTAMPTZ,
    CONSTRAINT uq_users_username   UNIQUE (username),
    CONSTRAINT ck_users_username   CHECK (username ~ '^[a-z][a-z0-9._]{2,29}$'),
    CONSTRAINT ck_users_status     CHECK (status IN ('PENDING_VERIFICATION','ACTIVE','SUSPENDED','DELETED')),
    CONSTRAINT ck_users_phone      CHECK (phone IS NULL OR phone ~ '^[6-9][0-9]{9}$'),
    CONSTRAINT ck_users_version    CHECK (version >= 1),
    -- deleted_at is set exactly when status is DELETED.
    CONSTRAINT ck_users_deleted    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),
    -- preferences must be a JSON object, not an array or a string.
    CONSTRAINT ck_users_prefs_obj  CHECK (jsonb_typeof(preferences) = 'object')
);

-- Email is unique ignoring case: "Selvi@x.in" and "selvi@x.in" are the same person.
-- A plain UNIQUE(email) would allow both.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE user_roles (
    user_id    BIGINT      NOT NULL,
    role_code  VARCHAR(20) NOT NULL,
    granted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pk_user_roles      PRIMARY KEY (user_id, role_code),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)   REFERENCES users (id),
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_code) REFERENCES roles (code)
);

CREATE TABLE addresses (
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    type        VARCHAR(10)  NOT NULL,
    is_primary  BOOLEAN      NOT NULL DEFAULT false,
    line1       VARCHAR(120) NOT NULL,
    line2       VARCHAR(120),
    city        VARCHAR(60)  NOT NULL,
    state       VARCHAR(60)  NOT NULL,
    pincode     CHAR(6)      NOT NULL,
    lat         NUMERIC(9,6),
    lon         NUMERIC(9,6),
    CONSTRAINT fk_addresses_user    FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_addresses_type    CHECK (type IN ('HOME','WORK','BILLING')),
    CONSTRAINT ck_addresses_pincode CHECK (pincode ~ '^[0-9]{6}$'),
    -- Either both coordinates or neither.
    CONSTRAINT ck_addresses_geo     CHECK ((lat IS NULL) = (lon IS NULL)),
    CONSTRAINT ck_addresses_lat     CHECK (lat IS NULL OR lat BETWEEN -90 AND 90),
    CONSTRAINT ck_addresses_lon     CHECK (lon IS NULL OR lon BETWEEN -180 AND 180)
);

-- Partial unique index: at most ONE primary address per user.
-- Rows with is_primary = false are not in the index, so a user can have many of those.
CREATE UNIQUE INDEX uq_addresses_one_primary ON addresses (user_id) WHERE is_primary;
CREATE INDEX idx_addresses_user ON addresses (user_id);
CREATE INDEX idx_user_roles_role ON user_roles (role_code);

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
