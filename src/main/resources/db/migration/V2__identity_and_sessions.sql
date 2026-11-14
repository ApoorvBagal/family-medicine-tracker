-- M1b: household, its single owner account (D12), and server-side sessions.

create table household (
    id                  uuid         primary key default gen_random_uuid(),
    name                text         not null check (length(trim(name)) between 1 and 100),
    timezone            text         not null default 'Asia/Kolkata',
    default_warn_days   int          not null default 5 check (default_warn_days between 0 and 60),
    created_at          timestamptz  not null default now()
);

create table app_user (
    id              uuid         primary key default gen_random_uuid(),
    household_id    uuid         not null references household (id) on delete restrict,
    email           text         not null check (email = lower(trim(email)) and length(email) between 3 and 254),
    display_name    text         not null check (length(trim(display_name)) between 1 and 100),
    password_hash   text         not null,
    created_at      timestamptz  not null default now(),
    last_login_at   timestamptz
);
create unique index app_user_email_uq on app_user (email);

-- Exactly one row ever (id = 1): inserting it is what completes first-time setup. A second, concurrent
-- setup attempt fails on the primary key, so two people can never both create the owner account.
create table installation (
    id            int          primary key check (id = 1),
    completed_at  timestamptz  not null default now(),
    owner_id      uuid         not null references app_user (id)
);

-- Spring Session JDBC schema (copied verbatim from spring-session-jdbc 4.1.1 schema-postgresql.sql;
-- managed by Flyway so it is versioned like everything else: spring.session.jdbc.initialize-schema=never).
CREATE TABLE SPRING_SESSION (
	PRIMARY_ID CHAR(36) NOT NULL,
	SESSION_ID CHAR(36) NOT NULL,
	CREATION_TIME BIGINT NOT NULL,
	LAST_ACCESS_TIME BIGINT NOT NULL,
	MAX_INACTIVE_INTERVAL INT NOT NULL,
	EXPIRY_TIME BIGINT NOT NULL,
	PRINCIPAL_NAME VARCHAR(100),
	CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);

CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
	SESSION_PRIMARY_ID CHAR(36) NOT NULL,
	ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
	ATTRIBUTE_BYTES BYTEA NOT NULL,
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
	CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES SPRING_SESSION(PRIMARY_ID) ON DELETE CASCADE
);
