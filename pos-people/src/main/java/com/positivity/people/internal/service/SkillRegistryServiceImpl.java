package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.SkillDto;
import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.entity.SkillCodeXref;
import com.positivity.people.internal.exception.UnknownSkillCodeException;
import com.positivity.people.internal.repository.SkillCodeXrefRepository;
import com.positivity.people.internal.repository.SkillRepository;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SkillRegistryServiceImpl implements SkillRegistryService {

    private final SkillRepository skillRepository;
    private final SkillCodeXrefRepository skillCodeXrefRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SkillDto> listActive() {
        Map<UUID, List<String>> sourceCodesBySkill =
                skillCodeXrefRepository.findAllByOrderBySourceCodeAscSourceSkillCodeAsc().stream()
                        .collect(Collectors.groupingBy(
                                xref -> xref.getSkill().getId(),
                                Collectors.mapping(
                                        xref -> xref.getSourceCode() + ":" + xref.getSourceSkillCode(),
                                        Collectors.toList())));
        return skillRepository.findAllByActiveTrueOrderByCodeAsc().stream()
                .map(skill -> SkillDto.builder()
                        .skillId(skill.getId())
                        .code(skill.getCode())
                        .name(skill.getName())
                        .competenceCode(skill.getCompetenceCode())
                        .minGvwrClass(skill.getMinGvwrClass())
                        .maxGvwrClass(skill.getMaxGvwrClass())
                        .sourceCodes(sourceCodesBySkill.getOrDefault(skill.getId(), List.of()))
                        .build())
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Skill resolve(@NonNull String sourceCode, @NonNull String sourceSkillCode) {
        String source = normalize(sourceCode);
        String code = normalize(sourceSkillCode);
        return skillCodeXrefRepository
                .findBySourceCodeAndSourceSkillCode(source, code)
                .map(SkillCodeXref::getSkill)
                .filter(Skill::isActive)
                .orElseThrow(() -> new UnknownSkillCodeException(source, code));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Skill requireByCode(@NonNull String code) {
        String normalized = normalize(code);
        return skillRepository
                .findByCode(normalized)
                .filter(Skill::isActive)
                .orElseThrow(() -> new UnknownSkillCodeException("DURION", normalized));
    }

    /** Upper-case and trim, applied to the lookup rather than by mutating stored values (spec D8). */
    static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
