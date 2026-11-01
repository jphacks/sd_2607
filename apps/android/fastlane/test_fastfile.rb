require "minitest/autorun"

# Evaluate the real lanes with recording actions; no Gradle, device, or Play access.
module UI
  def self.user_error!(message)
    raise ArgumentError, message
  end
end

class FastfileHarness
  def self.default_platform(*) = nil
  def self.desc(*) = nil
  def self.platform(*) = yield
  def self.lane(name, &block) = define_method(name, &block)

  module SharedValues
    GRADLE_AAB_OUTPUT_PATH = :GRADLE_AAB_OUTPUT_PATH
  end

  attr_reader :calls, :lane_context
  attr_accessor :fail_gradle, :aab

  def initialize
    @calls = []
    @lane_context = {}
    @aab = "/tmp/test-only.aab"
  end

  # Like fastlane, expose the built AAB only after a bundle task.
  def gradle(**options)
    @calls << [:gradle, options]
    raise "Gradle failed" if fail_gradle
    @lane_context[SharedValues::GRADLE_AAB_OUTPUT_PATH] = aab if options[:task].to_s.include?(":bundle")
  end

  def upload_to_play_store(**options)
    @calls << [:upload, options]
  end

  class_eval(File.read(File.join(__dir__, "Fastfile")), File.join(__dir__, "Fastfile"))
end

class FastfileTest < Minitest::Test
  OPTIONS = { app: "mosimo", version_code: "42", version_name: "0.2.0" }.freeze
  ENV_KEYS = FastfileHarness::SIGNING_ENV + ["SUPPLY_JSON_KEY_DATA"]

  def setup
    @saved_env = ENV_KEYS.to_h { |key| [key, ENV[key]] }
    ENV_KEYS.each { |key| ENV[key] = "test-only" }
    ENV["ANDROID_KEYSTORE_PATH"] = "/tmp/test-only-upload.keystore"
    @runner = FastfileHarness.new
  end

  def teardown
    @saved_env.each { |key, value| ENV[key] = value }
  end

  def test_ci_targets_the_selected_app_and_shared_tests_without_secrets
    ENV_KEYS.each { |key| ENV.delete(key) }
    @runner.ci(app: "mosimo")
    tasks = @runner.calls.fetch(0).last.fetch(:tasks)
    assert_includes tasks, ":bundle-core:test"
    assert_includes tasks, ":dtn7-bpv7:test"
    assert_includes tasks, ":mesh-android:testDebugUnitTest"
    assert_includes tasks, ":app:lintDemoDebug"
    assert_includes tasks, ":app:assembleDemoDebug"
    refute tasks.any? { |task| task.include?("Verification") }
  end

  def test_unknown_app_cannot_build_or_upload
    assert_raises(ArgumentError) { @runner.internal(OPTIONS.merge(app: "../other")) }
    assert_empty @runner.calls
  end

  def test_invalid_versions_fail_before_gradle
    [nil, "", "0", "-1", "1.5", "1; echo bad", "2100000001"].each do |code|
      assert_raises(ArgumentError) { @runner.build(OPTIONS.merge(version_code: code)) }
    end
    assert_raises(ArgumentError) { @runner.build(OPTIONS.merge(version_name: "  ")) }
    assert_empty @runner.calls
  end

  def test_every_signing_value_is_required
    FastfileHarness::SIGNING_ENV.each do |key|
      value = ENV.delete(key)
      assert_raises(ArgumentError) { @runner.build(OPTIONS) }
      ENV[key] = value
    end
    assert_empty @runner.calls
  end

  def test_missing_keystore_fails_before_gradle
    File.stub(:file?, false) do
      assert_raises(ArgumentError) { @runner.build(OPTIONS) }
    end
    assert_empty @runner.calls
  end

  def test_missing_play_credentials_fails_before_tests_or_build
    ENV.delete("SUPPLY_JSON_KEY_DATA")
    File.stub(:file?, true) do
      assert_raises(ArgumentError) { @runner.internal(OPTIONS) }
    end
    assert_empty @runner.calls
  end

  def test_internal_tests_then_builds_exactly_one_signed_variant_then_uploads
    File.stub(:file?, true) { @runner.internal(OPTIONS) }
    assert_equal [:gradle, :gradle, :upload], @runner.calls.map(&:first)
    build = @runner.calls[1].last
    assert_equal ":app:bundleDemoRelease", build.fetch(:task)
    assert_equal false, build.fetch(:print_command)
    assert_equal false, build.fetch(:print_command_output)
    assert_equal 42, build.fetch(:properties).fetch("android.injected.version.code")
    assert_equal "0.2.0", build.fetch(:properties).fetch("android.injected.version.name")
    assert_equal ENV.fetch("ANDROID_KEYSTORE_PATH"), build.fetch(:properties).fetch("android.injected.signing.store.file")
    upload = @runner.calls.last.last
    assert_equal "dev.oriom.mosimo", upload.fetch(:package_name)
    assert_equal "internal", upload.fetch(:track)
    assert_equal "completed", upload.fetch(:release_status)
    assert_equal "/tmp/test-only.aab", upload.fetch(:aab)
    %i[skip_upload_apk skip_upload_metadata skip_upload_images skip_upload_screenshots skip_upload_changelogs].each do |key|
      assert_equal true, upload.fetch(key)
    end
  end

  def test_gradle_failure_prevents_upload
    @runner.fail_gradle = true
    File.stub(:file?, true) do
      assert_raises(RuntimeError) { @runner.internal(OPTIONS) }
    end
    assert_equal [:gradle], @runner.calls.map(&:first)
  end

  def test_second_app_without_flavors_uses_its_own_tasks_and_package
    @runner.define_singleton_method(:android_app!) do |_options|
      { module: "messenger", flavor: "", package: "test.messenger" }
    end
    File.stub(:file?, true) { @runner.internal(OPTIONS) }
    assert_includes @runner.calls[0].last.fetch(:tasks), ":messenger:assembleDebug"
    assert_equal ":messenger:bundleRelease", @runner.calls[1].last.fetch(:task)
    upload = @runner.calls.last.last
    assert_equal "test.messenger", upload.fetch(:package_name)
  end

  def test_missing_aab_prevents_upload
    @runner.aab = nil
    File.stub(:file?, true) do
      assert_raises(ArgumentError) { @runner.internal(OPTIONS) }
    end
    assert_equal [:gradle, :gradle], @runner.calls.map(&:first)
  end
end
