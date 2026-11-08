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

  attr_reader :calls
  attr_accessor :fail_gradle

  def initialize
    @calls = []
  end

  def gradle(**options)
    @calls << [:gradle, options]
    raise "Gradle failed" if fail_gradle
  end

  def upload_to_play_store(**options)
    @calls << [:upload, options]
  end

  class_eval(File.read(File.join(__dir__, "Fastfile")), File.join(__dir__, "Fastfile"))
end

class FastfileTest < Minitest::Test
  def setup
    @runner = FastfileHarness.new
  end

  def test_ci_targets_the_selected_app_and_shared_tests
    @runner.ci(app: "mosimo")
    assert_equal [":dtn7-bpv7:test", ":bundle-core:test", ":mesh-android:testDebugUnitTest",
                  ":app:testDebugUnitTest", ":app:lintDebug", ":app:assembleDebug"],
                 @runner.calls.fetch(0).last.fetch(:tasks)
  end

  def test_unknown_app_cannot_build_or_upload
    assert_raises(ArgumentError) { @runner.internal(app: "../other") }
    assert_empty @runner.calls
  end

  def test_internal_tests_then_builds_the_bundle_then_uploads_only_the_aab_to_alpha
    @runner.internal(app: "mosimo")
    assert_equal [:gradle, :gradle, :upload], @runner.calls.map(&:first)
    assert_equal ":app:bundleRelease", @runner.calls[1].last.fetch(:task)
    assert_equal({ package_name: "dev.jisaku.mosimo", track: "alpha", skip_upload_apk: true }, @runner.calls[2].last)
  end

  def test_gradle_failure_prevents_upload
    @runner.fail_gradle = true
    assert_raises(RuntimeError) { @runner.internal(app: "mosimo") }
    assert_equal [:gradle], @runner.calls.map(&:first)
  end

  def test_hanashi_builds_in_its_own_gradle_root_and_uploads_its_package
    @runner.internal(app: "hanashi")
    assert_equal ["../messenger"] * 2, @runner.calls[0, 2].map { |c| c.last.fetch(:project_dir) }
    assert_includes @runner.calls[0].last.fetch(:tasks), ":mosimo-dm:test"
    assert_equal "dev.jisaku.hanashi", @runner.calls[2].last.fetch(:package_name)
  end
end
