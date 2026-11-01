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
  OPTIONS = { app: "mosimo" }.freeze
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
    assert_includes tasks, ":app:testDebugUnitTest"
    assert_includes tasks, ":app:lintDebug"
    assert_includes tasks, ":app:assembleDebug"
    refute tasks.any? { |task| task.match?(/Demo|Peer|Verification/) }
  end

  def test_unknown_app_cannot_build_or_upload
    assert_raises(ArgumentError) { @runner.internal(OPTIONS.merge(app: "../other")) }
    assert_empty @runner.calls
  end

  def test_version_overrides_are_rejected_before_gradle
    [{ version_code: "42" }, { version_name: "0.2.0" }].each do |version|
      assert_raises(ArgumentError) { @runner.build(OPTIONS.merge(version)) }
      assert_raises(ArgumentError) { @runner.internal(OPTIONS.merge(version)) }
    end
    assert_empty @runner.calls
  end

  def test_build_uses_gradle_versions_when_no_overrides_are_given
    File.stub(:file?, true) { @runner.build(app: "mosimo") }
    refute @runner.calls.fetch(0).last.key?(:properties)
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
    assert_equal ":app:bundleRelease", build.fetch(:task)
    assert_equal false, build.fetch(:print_command)
    assert_equal false, build.fetch(:print_command_output)
    refute build.key?(:properties), "Signing secrets and version overrides must not be passed as command-line properties"
    upload = @runner.calls.last.last
    assert_equal "dev.jisaku.mosimo", upload.fetch(:package_name)
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

  def test_second_app_uses_its_own_tasks_and_package
    @runner.define_singleton_method(:android_app!) do |_options|
      { module: "messenger", package: "test.messenger" }
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
