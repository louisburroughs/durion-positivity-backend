CREATE TABLE user_person_links (
    id UUID PRIMARY KEY,
    user_id VARCHAR22(255) NOT NULL,
    person_id UUID NOT NULL,
    link_type VARCHAR2(50) DEFAULT 'PRIMARY',
    created_at timestamptz NOT NULL,
    created_by VARCHAR2(255),
    notes VARCHAR2(1000),
    CONSTRAINT uk_user_person UNIQUE (user_id, person_id),
    CONSTRAINT fk_person FOREIGN KEY (person_id) REFERENCES person(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_person_links_user_id ON user_person_links(user_id);
CREATE INDEX idx_user_person_links_person_id ON user_person_links(person_id);
