package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.SkillDto;
import com.positivity.people.internal.entity.Skill;
import java.util.List;
import org.jspecify.annotations.NonNull;

/** The skill registry (CAP-328): read it, and resolve a vendor code onto it. */
public interface SkillRegistryService {

    /** Every active skill, with its vendor codes, ordered by code. */
    @NonNull
    List<SkillDto> listActive();

    /**
     * The registry skill a vendor's code maps onto. Normalises the source and code to upper-case
     * and trim (spec D8) — {@code ase}/{@code t4-brakes } resolves — and fails loudly on a code
     * the cross-reference does not know.
     *
     * @throws com.positivity.people.internal.exception.UnknownSkillCodeException when unmapped
     */
    @NonNull
    Skill resolve(@NonNull String sourceCode, @NonNull String sourceSkillCode);

    /** A registry skill by Durion code, upper-cased and trimmed; fails loudly when absent or inactive. */
    @NonNull
    Skill requireByCode(@NonNull String code);
}
