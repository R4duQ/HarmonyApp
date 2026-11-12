//! The engine's built-in self-test (also reachable from the app over JNI).

#[test]
fn self_test_passes() {
    let (report, passed) = harmonyshuffle::run_synthetic_brain_simulation();
    println!("{report}");
    assert!(passed, "{report}");
    assert!(report.contains("RESULT: PASS"));
}
