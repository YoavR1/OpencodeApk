# R8 configuration is deferred to M11 (docs/IMPLEMENTATION_PLAN.md).
#
# When it is enabled, the two things most likely to break are the WebView
# JavaScript bridge and anything reached only by reflection. Add the rules then,
# against real code, rather than guessing now.
