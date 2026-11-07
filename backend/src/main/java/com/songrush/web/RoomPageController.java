package com.songrush.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// A room link /r/{code} loads the single-page frontend, which reads the code from the URL. The forward
// is unconditional: with no static location, /index.html ends in the resource handler's 404 and so in
// the JSON 404 (cycle 054, review N1). /admin and /admin/ load the same page, which renders the owner's
// admin screen (cycle 083, D-097); nginx Basic Auth guards it, and its API checks the admin key.
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

}
