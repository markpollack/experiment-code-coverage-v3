#!/usr/bin/env bash
# Run the T2 practice-adherence judge against a test directory.
# Usage: ./scripts/run-judge.sh <test-dir> [prompt-file]
#
# Example:
#   ./scripts/run-judge.sh results/simple-n1/spring-petclinic-partial
#   ./scripts/run-judge.sh results/simple-n1/spring-petclinic-partial prompts/judge-practice-adherence.txt

set -euo pipefail

TEST_DIR="${1:?Usage: run-judge.sh <test-dir> [prompt-file]}"
PROMPT_FILE="${2:-prompts/judge-practice-adherence.txt}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ ! -d "$TEST_DIR" ]; then
    echo "Error: directory not found: $TEST_DIR" >&2
    exit 1
fi

if [ ! -f "$PROMPT_FILE" ]; then
    PROMPT_FILE="$PROJECT_ROOT/$PROMPT_FILE"
fi

if [ ! -f "$PROMPT_FILE" ]; then
    echo "Error: prompt file not found: $PROMPT_FILE" >&2
    exit 1
fi

RESULT_FILE=$(mktemp /tmp/claude-run-XXXXXX)
echo "$RESULT_FILE" > /tmp/claude-run-current

# Create a wrapper script that pipes the prompt to claude via stdin
WRAPPER=$(mktemp /tmp/judge-wrapper-XXXXXX.sh)
cat > "$WRAPPER" << 'INNER_EOF'
#!/usr/bin/env bash
cd "$1"
cat "$2" | claude --print --output-format text -
INNER_EOF
chmod +x "$WRAPPER"

echo "Judging: $TEST_DIR"
echo "Prompt: $PROMPT_FILE"
echo "Output: $RESULT_FILE"

"$WRAPPER" "$(cd "$TEST_DIR" && pwd)" "$PROMPT_FILE" > "$RESULT_FILE" 2>&1

echo "Done. Results in: $RESULT_FILE"
cat "$RESULT_FILE"

rm -f "$WRAPPER"
