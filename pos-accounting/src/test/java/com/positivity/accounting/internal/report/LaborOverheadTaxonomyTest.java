package com.positivity.accounting.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.enums.CostType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LaborOverheadTaxonomyTest {

    @Test
    void lines_returnsCanonicalOrderAndImmutableList() {
        List<LaborOverheadTaxonomy.LineDef> lines = LaborOverheadTaxonomy.lines();
        LaborOverheadTaxonomy.LineDef firstLine = lines.get(0);

        assertThat(lines).isNotEmpty();
        assertThat(firstLine.code()).isEqualTo("1.1");
        assertThat(lines.get(lines.size() - 1).code()).isEqualTo(LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD);

        assertThatThrownBy(() -> lines.add(firstLine)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void leafCodes_returnsOnlyLeafLinesInCanonicalOrder() {
        List<String> leafCodes = LaborOverheadTaxonomy.leafCodes();

        assertThat(leafCodes)
                .contains("1.1.1", "1.1.2", "2.11.4")
                .doesNotContain(
                        "1.1",
                        "1.3",
                        LaborOverheadTaxonomy.TOTAL_LABOR,
                        LaborOverheadTaxonomy.TOTAL_OVERHEAD,
                        LaborOverheadTaxonomy.TOTAL_LABOR_OVERHEAD);

        int leafCount = (int) LaborOverheadTaxonomy.lines().stream()
                .filter(line -> !line.isSubtotal())
                .count();
        assertThat(leafCodes).hasSize(leafCount);

        List<String> expectedCanonicalLeafOrder = new ArrayList<>();
        for (LaborOverheadTaxonomy.LineDef line : LaborOverheadTaxonomy.lines()) {
            if (!line.isSubtotal()) {
                expectedCanonicalLeafOrder.add(line.code());
            }
        }
        assertThat(leafCodes).containsExactlyElementsOf(expectedCanonicalLeafOrder);
    }

    @Test
    void byCode_returnsExpectedLeafSubtotalAndTotalDefinitions() {
        LaborOverheadTaxonomy.LineDef hourlyWages = LaborOverheadTaxonomy.byCode("1.1.1");
        assertThat(hourlyWages).isNotNull();
        assertThat(hourlyWages.parentCode()).isEqualTo("1.1");
        assertThat(hourlyWages.level()).isEqualTo(3);
        assertThat(hourlyWages.costType()).isEqualTo(CostType.VARIABLE);
        assertThat(hourlyWages.isSubtotal()).isFalse();
        assertThat(hourlyWages.usdOnly()).isFalse();
        assertThat(hourlyWages.childCodes()).isEmpty();

        LaborOverheadTaxonomy.LineDef equipmentDepreciationUsdOnly = LaborOverheadTaxonomy.byCode("2.11.4");
        assertThat(equipmentDepreciationUsdOnly).isNotNull();
        assertThat(equipmentDepreciationUsdOnly.isSubtotal()).isFalse();
        assertThat(equipmentDepreciationUsdOnly.usdOnly()).isTrue();
        assertThat(equipmentDepreciationUsdOnly.costType()).isEqualTo(CostType.FIXED);

        LaborOverheadTaxonomy.LineDef taxesAndBenefits = LaborOverheadTaxonomy.byCode("1.3");
        assertThat(taxesAndBenefits).isNotNull();
        assertThat(taxesAndBenefits.isSubtotal()).isTrue();
        assertThat(taxesAndBenefits.costType()).isNull();
        assertThat(taxesAndBenefits.childCodes()).containsExactly("1.3.1", "1.3.2", "1.3.3", "1.3.4", "1.3.5", "1.3.6");

        LaborOverheadTaxonomy.LineDef totalOverhead =
                LaborOverheadTaxonomy.byCode(LaborOverheadTaxonomy.TOTAL_OVERHEAD);
        assertThat(totalOverhead).isNotNull();
        assertThat(totalOverhead.level()).isEqualTo(1);
        assertThat(totalOverhead.parentCode()).isNull();
        assertThat(totalOverhead.isSubtotal()).isTrue();
        assertThat(totalOverhead.childCodes())
                .containsExactly(
                        "2.1", "2.2", "2.3", "2.4", "2.5", "2.6", "2.7", "2.8", "2.9", "2.10", "2.11", "2.12", "2.13",
                        "2.14", "2.15");
    }

    @Test
    void byCode_returnsNullForUnknownCode() {
        assertThat(LaborOverheadTaxonomy.byCode("does-not-exist")).isNull();
    }
}
