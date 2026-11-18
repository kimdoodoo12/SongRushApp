{{flutter_js}}
{{flutter_build_config}}

// Cycle 158: starts the app without Flutter's deprecated service worker.
// Flutter's default bootstrap passes serviceWorkerSettings, which makes the
// loader re-register flutter_service_worker.js on any browser that already
// has a registration and wait for it before loading main.dart.js. That
// worker now only unregisters itself and reloads the page, so a phone that
// installed it from an earlier deploy could stay on the loading screen.
// index.html removes such old registrations; nothing registers a new one.
//
// It also records each start-up stage in the cycle-157 diagnostics
// (window.__srDiag), so the loading screen shows where a phone stops.
(function () {
  function mark(text) {
    try {
      var diag = window.__srDiag || (window.__srDiag = []);
      diag.push(String(text).slice(0, 160));
      if (diag.length > 10) diag.shift();
    } catch (ignored) {
      // Recording must never stop the app from starting.
    }
  }
  function stage(name) {
    mark('stage: ' + name + ' @' + Math.round(performance.now()) + 'ms');
  }
  async function step(name, run) {
    try {
      return await run();
    } catch (e) {
      mark('stage failed: ' + name + ': ' + (e && e.message ? e.message : e));
      throw e;
    }
  }

  // The same configuration Flutter's default bootstrap uses: none of its own.
  // CanvasKit's location (local, from useLocalCanvasKit) comes from
  // _flutter.buildConfig, which the loader reads itself.
  var config = {};
  stage('loader started');
  _flutter.loader.load({
    config: config,
    onEntrypointLoaded: async function (engineInitializer) {
      stage('entrypoint loaded');
      var appRunner = await step('engine', function () {
        return engineInitializer.initializeEngine(config);
      });
      stage('engine initialized');
      await step('runApp', function () {
        return appRunner.runApp();
      });
      stage('app running');
    }
  });
})();
