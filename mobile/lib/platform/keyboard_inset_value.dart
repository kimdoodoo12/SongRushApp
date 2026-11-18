/// The on-screen keyboard's height in CSS (logical) pixels from the
/// browser's viewports: the part of the window below the visual viewport,
/// `innerHeight - visualViewport.height - visualViewport.offsetTop`. Chrome
/// on Android overlays the keyboard without resizing the page
/// ("resizes-visual"): only the visual viewport shrinks. Under 1 px (a
/// rounding difference), negative or not a finite number is 0.
double keyboardInsetFrom(num innerHeight, num viewportHeight, num viewportOffsetTop) {
  final inset = (innerHeight - viewportHeight - viewportOffsetTop).toDouble();
  if (!inset.isFinite || inset < 1) return 0;
  return inset;
}
