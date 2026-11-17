package com.songrush.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// A room link /r/{code} loads the single-page frontend, which reads the code from the URL. The forward
// is unconditional: with no static location, /index.html ends in the resource handler's 404 and so in
// the JSON 404 (cycle 054, review N1). /admin and /admin/ load the same page, which renders the owner's
// admin screen (cycle 083, D-097); nginx Basic Auth guards it, and its API checks the admin key.
// The Flutter mobile web build lives under /m/ (cycle 154, D-140): /m redirects to /m/ so its relative
// <base href="/m/"> resolves, and /m/ and /m/r/{code} load its index.html, which reads the code.
@Controller
public class RoomPageController {

	@GetMapping({ "/r/{code}", "/r/{code}/" })
	public String room() {
		return "forward:/index.html";
	}

	@GetMapping({ "/admin", "/admin/" })
	public String admin() {
		return "forward:/index.html";
	}

	@GetMapping("/m")
	public String mobileSlash() {
		return "redirect:/m/";
	}

	@GetMapping({ "/m/", "/m/r/{code}", "/m/r/{code}/" })
	public String mobile() {
		return "forward:/m/index.html";
	}

}
