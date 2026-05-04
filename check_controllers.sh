#!/bin/bash
DIR="pos-accounting/src/main/java/com/positivity/accounting/internal/controller"
for f in "$DIR"/*.java; do
    [[ "$f" == *ExceptionHandler.java ]] && continue
    echo "Processing $(basename "$f")..."
    GAPS=""
    
    # Class-level @Tag check
    if ! grep -q "@Tag" "$f"; then
        GAPS="${GAPS}\n  - Missing class-level @Tag"
    fi

    WHICH_GAP=$(awk '
    /@Operation/ { 
        has_op=1; 
        if ($0 !~ /summary/) printf "  - @Operation missing summary at line %d\n", NR;
        if ($0 !~ /description/) printf "  - @Operation missing description at line %d\n", NR;
        if ($0 !~ /tags/) printf "  - @Operation missing tags at line %d\n", NR;
    }
    /@(Get|Post|Put|Delete|Patch)Mapping/ {
        if (!has_op) printf "  - Method missing @Operation at line %d\n", NR;
        has_op=0;
    }
    /public/ { if ($0 !~ /class/) has_op=0; } 
    ' "$f")
    
    GAPS="${GAPS}${WHICH_GAP}"

    if [[ -z "$GAPS" ]]; then
        echo "  Result: Compliant"
    else
        echo -e "  Gaps:$GAPS"
    fi
    echo "-----------------------------------"
done
