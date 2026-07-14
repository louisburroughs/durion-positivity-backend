-- pos-people-contact baseline schema (ADR-0044 Phase 3, issue #874).
-- Identity/contact/link authority split out of pos-people: person is identity only
-- (employment stays in pos-people); user_person_links is keyed by username;
-- contact_info_json on the entity is a derived @Formula (not stored).
-- person.legal_name was retired in pos-people (issue #726) and is not carried over.

create table person (
    id UUID not null,
    first_name varchar(255),
    last_name varchar(255),
    preferred_name varchar(255),
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create table person_contact_point (
    id UUID not null,
    person_id uuid not null,
    contact_type varchar(20) not null check ((contact_type in ('EMAIL','PHONE_MOBILE','PHONE_HOME','PHONE_WORK'))),
    "value" varchar(255) not null,
    is_primary boolean not null,
    created_at timestamp(6) with time zone,
    updated_at timestamp(6) with time zone,
    primary key (id)
);

create table user_person_links (
    id UUID not null,
    person_id UUID not null,
    username varchar(255) not null unique,
    status varchar(20) not null check ((status in ('ACTIVE','INACTIVE'))),
    link_type varchar(50),
    notes varchar(1000),
    created_by varchar(255),
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone,
    primary key (id),
    constraint fk_user_person_links_person foreign key (person_id) references person
);

create index idx_person_contact_point_person on person_contact_point (person_id);
create index idx_person_contact_point_type on person_contact_point (contact_type);
create index idx_user_person_links_person_id on user_person_links (person_id);
