package com.tchip.carlauncher.library.chart;

import com.tchip.carlauncher.library.chart.LineData;
import com.tchip.carlauncher.library.chart.FillFormatter;

public interface LineDataProvider extends BarLineScatterCandleDataProvider {

	public LineData getLineData();

	/**
	 * Sets a custom FillFormatter to the chart that handles the position of the
	 * filled-line for each DataSet. Set this to null to use the default logic.
	 * 
	 * @param formatter
	 */
	public void setFillFormatter(FillFormatter formatter);

	/**
	 * Returns the FillFormatter that handles the position of the filled-line.
	 * 
	 * @return
	 */
	public FillFormatter getFillFormatter();
}
