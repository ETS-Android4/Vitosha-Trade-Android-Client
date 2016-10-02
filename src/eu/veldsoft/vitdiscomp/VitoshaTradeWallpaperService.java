package eu.veldsoft.vitdiscomp;

import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.encog.engine.network.activation.ActivationSigmoid;
import org.encog.ml.data.MLData;
import org.encog.ml.data.MLDataSet;
import org.encog.ml.data.basic.BasicMLData;
import org.encog.ml.data.basic.BasicMLDataSet;
import org.encog.neural.networks.BasicNetwork;
import org.encog.neural.networks.layers.BasicLayer;
import org.encog.neural.networks.training.propagation.resilient.ResilientPropagation;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

public class VitoshaTradeWallpaperService extends WallpaperService {
	private static final double TRAINING_TIME_PERCENT_FROM_DELAY = 1D;

	private static final Random PRNG = new Random();

	private static int top = 0;

	private static int left = 0;

	private static long delay = 0;

	private static int width = 0;

	private static int height = 0;

	private static boolean visible = false;

	private static Bitmap images[] = null;

	private static BasicNetwork network = new BasicNetwork();

	private static MLDataSet examples = null;

	private static MLData forecast = null;

	private static MLData output = null;

	private static ResilientPropagation train = null;

	static {
		// TODO Load ANN structure from the remote server.
		Map<Integer, Integer> counters = new HashMap<Integer, Integer>();
		counters.put(InputData.REGULAR, 0);
		counters.put(InputData.BIAS, 0);
		counters.put(InputData.INPUT, 0);
		counters.put(InputData.OUTPUT, 0);

		for (int type : InputData.NEURONS) {
			counters.put(type, counters.get(type) + 1);
		}

		int inputSize = counters.get(InputData.INPUT);
		int hiddenSize = counters.get(InputData.REGULAR);
		int outputSize = counters.get(InputData.OUTPUT);

		/*
		 * Network construction.
		 */
		network.addLayer(new BasicLayer(null, true, counters.get(InputData.INPUT)));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), true, counters.get(InputData.REGULAR)));
		network.addLayer(new BasicLayer(new ActivationSigmoid(), false, counters.get(InputData.OUTPUT)));
		network.getStructure().finalizeStructure();
		network.reset();

		// TODO Load weights to the network.

		double values[] = InputData.RATES[PRNG.nextInt(InputData.RATES.length)];

		/*
		 * Normalize data.
		 */
		double min = values[0];
		double max = values[0];
		for (double value : values) {
			if (value < min) {
				min = value;
			}
			if (value > max) {
				max = value;
			}
		}

		/*
		 * Prepare training set.
		 */
		double input[][] = new double[values.length - (inputSize + outputSize)][inputSize];
		double target[][] = new double[values.length - (inputSize + outputSize)][outputSize];
		for (int i = 0; i < values.length - (inputSize + outputSize); i++) {
			for (int j = 0; j < inputSize; j++) {
				input[i][j] = 0.1 + 0.8 * (values[i + j] - min) / (max - min);
			}
			for (int j = 0; j < outputSize; j++) {
				target[i][j] = 0.1 + 0.8 * (values[i + inputSize + j] - min) / (max - min);
			}
		}
		examples = new BasicMLDataSet(input, target);

		/*
		 * Prepare forecast set.
		 */
		input = new double[1][inputSize];
		for (int j = 0; j < inputSize; j++) {
			input[0][j] = 0.1 + 0.8 * (values[values.length - inputSize + j] - min) / (max - min);
		}
		forecast = new BasicMLData(input[0]);
	}

	private class WallpaperEngine extends Engine {
		private final Handler handler = new Handler();

		private final Runnable trainer = new Runnable() {
			@Override
			public void run() {
				predict();
				draw();
				train();
			}
		};

		private void train() {
			if (network == null) {
				return;
			}
			if (examples == null) {
				return;
			}

			train = new ResilientPropagation(network, examples);
			train.iteration();
			train.finishTraining();
		}

		private void predict() {
			if (forecast == null) {
				return;
			}

			output = network.compute(forecast);
		}

		private void draw() {
			if (images == null) {
				return;
			}

			SurfaceHolder holder = getSurfaceHolder();
			Canvas canvas = null;

			Bitmap image = images[Calendar.getInstance().get(Calendar.DAY_OF_YEAR) % images.length];
			left = PRNG.nextInt(image.getWidth() - width);
			top = PRNG.nextInt(image.getHeight() - height);

			try {
				canvas = holder.lockCanvas();
				if (canvas != null) {
					canvas.drawBitmap(image, new Rect(left, top, left + width - 1, top + height - 1),
							new Rect(0, 0, width - 1, height - 1), null);

					/*
					 * Panels.
					 */
					Paint paint = new Paint();
					paint.setColor(Color.argb(63, 0, 0, 0));
					canvas.drawRect(width / 2 - 50, height / 2 - 160, width / 2 + 50, height / 2 - 60, paint);
					canvas.drawRect(width / 2 - 50, height / 2 - 50, width / 2 + 50, height / 2 + 50, paint);
					canvas.drawRect(width / 2 - 50, height / 2 + 60, width / 2 + 50, height / 2 + 160, paint);

					/*
					 * Time series info.
					 */
					paint.setColor(Color.argb(95, 255, 255, 255));
					canvas.drawText("" + InputData.SYMBOL, width / 2 - 40, height / 2 - 140, paint);
					canvas.drawText("" + InputData.PERIOD, width / 2 - 40, height / 2 - 120, paint);

					/*
					 * Forecast.
					 */
					int x = width / 2 - 50;
					int y = height / 2 + 50;
					paint.setColor(Color.argb(95, 0, 255, 0));
					for (int i = 0; forecast.getData() != null && i < forecast.getData().length; i++) {
						canvas.drawLine(x, y, x, y - (int) (forecast.getData()[i] * 100D), paint);
						x++;
					}
					paint.setColor(Color.argb(95, 255, 0, 0));
					for (int i = 0; output.getData() != null && i < output.getData().length; i++) {
						canvas.drawLine(x, y, x, y - (int) (output.getData()[i] * 100D), paint);
						x++;
					}

					/*
					 * Artificial neural network.
					 */
					int k = 0;
					int l = 0;
					double topology[][] = { forecast.getData(),
							new double[network.getLayerNeuronCount(0) * network.getLayerNeuronCount(1)],
							new double[network.getLayerNeuronCount(1)],
							new double[network.getLayerNeuronCount(1) * network.getLayerNeuronCount(2)],
							output.getData() };
					for (int i = 0, m = 0, n = 0; i < topology[1].length; i++) {
						if (n >= network.getLayerNeuronCount(1)) {
							n = 0;
							m++;
						}
						if (m >= network.getLayerNeuronCount(0)) {
							m = 0;
						}
						topology[1][i] = network.getWeight(0, m, n);
						n++;
					}
					for (int i = 0, m = 0, n = 0; i < topology[3].length; i++) {
						if (n >= network.getLayerNeuronCount(2)) {
							n = 0;
							m++;
						}
						if (m >= network.getLayerNeuronCount(1)) {
							m = 0;
						}
						topology[3][i] = network.getWeight(1, m, n);
						n++;
					}

					/*
					 * Hidden layer values.
					 */
					for (int i = 0; i < network.getLayerNeuronCount(1); i++) {
						topology[2][i] = network.getLayerOutput(1, i);
					}

					/*
					 * Normalize weights.
					 */
					double min = Double.MAX_VALUE;
					double max = Double.MIN_VALUE;
					for (double value : topology[1]) {
						if (value < min) {
							min = value;
						}
						if (value > max) {
							max = value;
						}
					}
					for (double value : topology[3]) {
						if (value < min) {
							min = value;
						}
						if (value > max) {
							max = value;
						}
					}
					for (int i = 0; i < topology[1].length; i++) {
						topology[1][i] = (topology[1][i] - min) / (max - min);
					}
					for (int i = 0; i < topology[3].length; i++) {
						topology[3][i] = (topology[3][i] - min) / (max - min);
					}

					/*
					 * Draw topology.
					 */
					int colors[] = { Color.argb(95, 0, 255, 0), Color.argb(95, 255, 255, 255),
							Color.argb(95, 0, 0, 255), Color.argb(95, 255, 255, 255), Color.argb(95, 255, 0, 0) };
					for (x = width / 2 - 2, k = 0; x <= width / 2 + 2; x++, k++) {
						for (y = height / 2 + 60, l = 0; y <= height / 2 + 160 && l < topology[k].length; y++, l++) {
							paint.setColor(colors[k]);
							paint.setColor(
									Color.argb(Color.alpha(colors[k]), (int) (Color.red(colors[k]) * topology[k][l]),
											(int) (Color.green(colors[k]) * topology[k][l]),
											(int) (Color.blue(colors[k]) * topology[k][l])));
							canvas.drawPoint(x, y, paint);
						}
					}
				}
			} finally {
				if (canvas != null) {
					holder.unlockCanvasAndPost(canvas);
				}
			}

			handler.removeCallbacks(trainer);
			if (visible == true) {
				handler.postDelayed(trainer, delay);
			}
		}

		public WallpaperEngine() {
			super();

			SharedPreferences preferences = PreferenceManager
					.getDefaultSharedPreferences(VitoshaTradeWallpaperService.this);

			top = Integer.parseInt(preferences.getString("top_corner", "0"));
			left = Integer.parseInt(preferences.getString("left_corner", "0"));
			delay = Long.parseLong(preferences.getString("loading", "" + 86400000));

			handler.post(trainer);
		}

		@Override
		public void onVisibilityChanged(boolean visible) {
			VitoshaTradeWallpaperService.this.visible = visible;

			if (visible == true) {
				handler.post(trainer);
			} else {
				handler.removeCallbacks(trainer);
			}
		}

		@Override
		public void onSurfaceDestroyed(SurfaceHolder holder) {
			super.onSurfaceDestroyed(holder);
			VitoshaTradeWallpaperService.this.visible = false;
			handler.removeCallbacks(trainer);
		}

		@Override
		public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
			VitoshaTradeWallpaperService.this.width = width;
			VitoshaTradeWallpaperService.this.height = height;
			super.onSurfaceChanged(holder, format, width, height);
		}
	}

	@Override
	public Engine onCreateEngine() {
		// TODO Find better place to initialize images.
		if (images == null) {
			images = new Bitmap[] {
					BitmapFactory.decodeResource(this.getResources(),
							R.drawable.vitosha_mountain_dimitar_petarchev_001),
					BitmapFactory.decodeResource(this.getResources(),
							R.drawable.vitosha_mountain_dimitar_petarchev_002),
					BitmapFactory.decodeResource(this.getResources(),
							R.drawable.vitosha_mountain_dimitar_petarchev_003),
					BitmapFactory.decodeResource(this.getResources(),
							R.drawable.vitosha_mountain_dimitar_petarchev_004), };
		}

		return new WallpaperEngine();
	}
}
