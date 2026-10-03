## WHAT
<!-- What changed? Keep this concrete and observable. -->

## WHY
<!-- What problem does this solve? -->

## IN SCOPE
<!-- Files, behaviors, or systems intentionally changed. -->

## OUT OF SCOPE
<!-- Related areas deliberately left untouched. -->

## INVARIANTS / MUST NOT CHANGE
<!-- Existing behavior, data, identity, signing, persistence, or compatibility that must remain stable. -->

## EDGE CASES
<!-- Important boundaries and failure modes considered. -->

## ACCEPTANCE
<!-- Concrete evidence that proves the requested behavior is complete. -->

## TESTS
<!-- Automated checks/tests run or added. Do not claim checks that were not run. -->

## RUNTIME PROOF
<!-- Is emulator/device proof required? Why or why not? -->

## RISK
<!-- Likely regression surfaces and risk level: low / normal / high. -->

## CI / RELEASE SAFETY
- [ ] Change scope is classified and the appropriate CI gates are allowed to run.
- [ ] No existing validation/coverage was removed merely to make CI faster.
- [ ] No unrelated production behavior was changed.
- [ ] Release/signing/application identity behavior is unchanged unless explicitly in scope.
- [ ] If CI/workflows changed, every removed or consolidated check has a documented replacement.
