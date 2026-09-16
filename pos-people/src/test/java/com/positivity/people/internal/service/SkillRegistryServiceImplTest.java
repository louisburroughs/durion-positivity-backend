package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.SkillDto;
import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.entity.SkillCodeXref;
import com.positivity.people.internal.exception.UnknownSkillCodeException;
import com.positivity.people.internal.repository.SkillCodeXrefRepository;
import com.positivity.people.internal.repository.SkillRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The registry read and the vendor-code resolution (CAP-328, spec D8). */
@ExtendWith(MockitoExtension.class)
class SkillRegistryServiceImplTest {

    private static final Skill BRAKES_LIGHT = skill("BRAKES-LIGHT", "BRAKES", 1, 3, true);
    private static final Skill BRAKES_HEAVY = skill("BRAKES-MEDIUM_HEAVY", "BRAKES", 4, 8, true);
    private static final Skill RETIRED = skill("RETIRED-THING", "RETIRED", 1, 8, false);

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillCodeXrefRepository xrefRepository;

    @InjectMocks
    private SkillRegistryServiceImpl service;

    @Test
    @DisplayName("resolve: the vendor code is normalised to upper-case and trimmed before the lookup")
    void resolveNormalises() {
        when(xrefRepository.findBySourceCodeAndSourceSkillCode("ASE", "T4-BRAKES"))
                .thenReturn(Optional.of(xref(BRAKES_HEAVY, "T4-BRAKES")));

        assertThat(service.resolve(" ase", "t4-brakes ")).isSameAs(BRAKES_HEAVY);
    }

    @Test
    @DisplayName("resolve: an unmapped code fails loudly, naming source and code")
    void resolveUnknownFailsLoudly() {
        when(xrefRepository.findBySourceCodeAndSourceSkillCode("ASE", "T3-ALIGN"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("ASE", "T3-ALIGN"))
                .isInstanceOf(UnknownSkillCodeException.class)
                .hasMessageContaining("ASE")
                .hasMessageContaining("T3-ALIGN");
    }

    @Test
    @DisplayName("resolve: a mapping onto a switched-off skill is as unknown as no mapping")
    void resolveInactiveFailsLoudly() {
        when(xrefRepository.findBySourceCodeAndSourceSkillCode("ASE", "X9-OLD"))
                .thenReturn(Optional.of(xref(RETIRED, "X9-OLD")));

        assertThatThrownBy(() -> service.resolve("ASE", "X9-OLD")).isInstanceOf(UnknownSkillCodeException.class);
    }

    @Test
    @DisplayName("requireByCode: Durion codes normalise the same way")
    void requireByCodeNormalises() {
        when(skillRepository.findByCode("BRAKES-LIGHT")).thenReturn(Optional.of(BRAKES_LIGHT));
        assertThat(service.requireByCode(" brakes-light ")).isSameAs(BRAKES_LIGHT);
    }

    @Test
    @DisplayName("listActive: ordered by code, each skill carrying its SOURCE:CODE cross-references")
    void listActiveCarriesSourceCodes() {
        when(skillRepository.findAllByActiveTrueOrderByCodeAsc()).thenReturn(List.of(BRAKES_LIGHT, BRAKES_HEAVY));
        when(xrefRepository.findAllByOrderBySourceCodeAscSourceSkillCodeAsc())
                .thenReturn(List.of(xref(BRAKES_LIGHT, "A5-BRAKES"), xref(BRAKES_HEAVY, "T4-BRAKES")));

        List<SkillDto> skills = service.listActive();

        assertThat(skills).extracting(SkillDto::getCode).containsExactly("BRAKES-LIGHT", "BRAKES-MEDIUM_HEAVY");
        assertThat(skills.get(0).getSourceCodes()).containsExactly("ASE:A5-BRAKES");
        assertThat(skills.get(0).getMinGvwrClass()).isEqualTo(1);
        assertThat(skills.get(0).getMaxGvwrClass()).isEqualTo(3);
        assertThat(skills.get(1).getSourceCodes()).containsExactly("ASE:T4-BRAKES");
    }

    @Test
    @DisplayName("a skill applies to exactly the classes in its range — class 3 is light, class 4 is not")
    void appliesToGvwrClassIsTheRange() {
        assertThat(BRAKES_LIGHT.appliesToGvwrClass(3)).isTrue();
        assertThat(BRAKES_LIGHT.appliesToGvwrClass(4)).isFalse();
        assertThat(BRAKES_HEAVY.appliesToGvwrClass(4)).isTrue();
        assertThat(BRAKES_HEAVY.appliesToGvwrClass(3)).isFalse();
    }

    private static Skill skill(String code, String competence, int min, int max, boolean active) {
        return Skill.builder()
                .id(UUID.nameUUIDFromBytes(code.getBytes()))
                .code(code)
                .name(code)
                .competenceCode(competence)
                .minGvwrClass(min)
                .maxGvwrClass(max)
                .active(active)
                .build();
    }

    private static SkillCodeXref xref(Skill skill, String sourceSkillCode) {
        return SkillCodeXref.builder()
                .id(UUID.nameUUIDFromBytes(("ASE:" + sourceSkillCode).getBytes()))
                .skill(skill)
                .sourceCode("ASE")
                .sourceSkillCode(sourceSkillCode)
                .build();
    }
}
