function updateControllers() {
	var controllers = d3.select('#controllers').selectAll('.controller').data(model.controllers);
	controllers.enter().append('div')
		.each(function (c) {
			controllerColorMap[c] = colors.pop();
			d3.select(document.body).classed(controllerColorMap[c] + '-selected', true);
		})
		.text(function (d) {
			return d;
		})
		.append('div')
		.attr('class', 'black-eye');

	controllers.attr('class', function (d) {
			var color = 'colorInactive';
			if (model.activeControllers.indexOf(d) != -1) {
				color = controllerColorMap[d];
			}
			var className = 'controller ' + color;
			return className;
		});

	// this should never be needed
	// controllers.exit().remove();

	controllers.on('dblclick', function (c) {
		if (model.activeControllers.indexOf(c) != -1) {
			var prompt = 'Dectivate ' + c + '?';
			var that = this;
			doConfirm(prompt, function (result) {
				if (result) {
					controllerDown(c);
					setPending(d3.select(that));
				};
			})
		} else {
			var prompt = 'Activate ' + c + '?';
			var that = this;
			doConfirm(prompt, function (result) {
				if (result) {
					controllerUp(c);
					setPending(d3.select(that));
				};
			});
		}
	});

	controllers.select('.black-eye').on('click', function (c) {
		var allSelected = true;
		for (var key in controllerColorMap) {
			if (!d3.select(document.body).classed(controllerColorMap[key] + '-selected')) {
				allSelected = false;
				break;
			}
		}
		if (allSelected) {
			for (var key in controllerColorMap) {
				d3.select(document.body).classed(controllerColorMap[key] + '-selected', key == c)
			}
		} else {
			for (var key in controllerColorMap) {
				d3.select(document.body).classed(controllerColorMap[key] + '-selected', true)
			}
		}

		// var selected = d3.select(document.body).classed(controllerColorMap[c] + '-selected');
		// d3.select(document.body).classed(controllerColorMap[c] + '-selected', !selected);
	});


}