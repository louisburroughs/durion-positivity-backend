# pos-people Phase 3.2 Cutover (ADR-0044 §6, #875)

`V5__drop_identity_tables.sql` drops `person`, `person_contact_point`, and `user_person_links`
from the pos-people schema — identity ownership moved to pos-people-contact (#874). On any
environment with real data, the rows must be copied to the pos-people-contact database **before**
the new pos-people image runs its migrations.

## Order of operations (alpha)

1. Deploy pos-people-contact (#874) and verify its Flyway baseline + seeds applied.
2. Copy identity rows from `pos_people_db` to `pos_people_contact_db` (same column shapes;
   `person.legal_name` no longer exists on either side):

   ```bash
   pg_dump -h <host> -U <user> -d pos_people_db \
     --data-only --column-inserts \
     -t person -t person_contact_point -t user_person_links \
   | psql -h <host> -U <user> -d pos_people_contact_db
   ```

   Conflicts with the people-contact seeds are expected for seed ids; rerun with
   `ON_ERROR_ROLLBACK=on` (`psql -v ON_ERROR_ROLLBACK=on`) or pre-delete seed rows — both sides
   seed identical ids, so collisions are same-data.
3. Verify counts match on both sides for the three tables.
4. Deploy the new pos-people image; V4 creates the outbox/replica tables, V5 drops the moved
   tables.
5. Enable the event flow (`POS_PEOPLE_CONTACT_KAFKA_ENABLED=true`, `POS_PEOPLE_KAFKA_ENABLED=true`)
   and request an outbox replay (`people-contact.outbox.replay-requested`) covering the cutover
   window, so `ext_people_contact_person` / `ext_people_contact_user_link` seed from facts rather
   than the dev bootstrap seeds.

Dev/docker environments need none of this: fresh databases apply both baselines and the
repeatable seeds insert matching ids on both sides.
