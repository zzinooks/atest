package com.web.root;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class MyController {

	@RequestMapping("helloWorld")
	public String helloWorld() {
		return "helloWorld";
	}
}
