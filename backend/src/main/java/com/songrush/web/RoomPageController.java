package com.songrush.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

// A room link /r/{code} loads the single-page frontend, which reads the code from the URL. The forward
// is unconditional: with no static location, /index.html ends in the resource handler's 404 and so in
// the JSON 404 (cycle 054, review N1).
@Controller
public class RoomPageController {

	@GetMapping({ "/r/{code}", "/r/{code}/" })
	public String room() {
		return "forward:/index.html";
	}

}
