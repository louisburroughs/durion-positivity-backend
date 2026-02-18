-- Ensure each user_id is associated to at most one person.
-- If duplicates already exist, keep one deterministic record per user_id and remove the rest.
WITH ranked_links AS (
    SELECT
        id,
        ROW_NUMBER() OVER (
            PARTITION BY user_id
            ORDER BY
                CASE WHEN status = 'ACTIVE' THEN 0 ELSE 1 END,
                created_at DESC NULLS LAST,
                id DESC
        ) AS rn
    FROM user_person_links
)
DELETE FROM user_person_links upl
USING ranked_links r
WHERE upl.id = r.id
  AND r.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS idx_user_person_links_user_id_unique
    ON user_person_links(user_id);
