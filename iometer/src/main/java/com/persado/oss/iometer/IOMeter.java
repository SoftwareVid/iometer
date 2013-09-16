package com.persado.oss.iometer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class IOMeter {

	private static final int DEFAULT_BUFSIZE = 4 * 1024;
	private static final long DEFAULT_FILESIZE = 2 * 1024 * 1024 * 1024L;
	private static final int DEFAULT_THREADS = Runtime.getRuntime()
			.availableProcessors() * 2;

	private ExecutorService pool = Executors.newCachedThreadPool();

	private int threads;
	private long fileSize;
	private int bufferSize;
	private byte[] dataBlock;
	
	private int availablePoints;
	private int seeksToTry;

	private Random random = new Random( System.nanoTime() );
	
	public IOMeter(int _threads, long _filesize, int _buffersize) {
		threads = _threads;
		fileSize = _filesize;
		bufferSize = _buffersize;
		long fileSizeMB = fileSize / 1024
				/ 1024;
		log("Configured with " + threads + " Threads, " + fileSizeMB + "MB test file size, " + bufferSize
				+ " bytes buffer size.");
		dataBlock = new byte[bufferSize];
		for (int i = 0; i < bufferSize; i++) {
			dataBlock[i] =  (byte) (Math.random()*255.0);
		}
		
		availablePoints = (int) (fileSize/bufferSize) - 2;
		seeksToTry = Math.max(5000, availablePoints/1000);
		log("Will create temporary files of "+threads* fileSizeMB +"MB. ");
		log("WARNING: If your machine has more memory than this, the test may be invalid.");
		log("Out of "+availablePoints+" slots, will try "+seeksToTry+" in the generated files.");
		
	}

	/**
	 * dummy log entry
	 * 
	 * @param message
	 */
	public static void log(String message) {
		SimpleDateFormat sdf = new SimpleDateFormat("yyMMdd-HH:mm:SS");
		System.out.println(sdf.format(new Date()) + " ["
				+ Thread.currentThread().getName() + "] " + message);
	}

	public static void main(String[] args) {
		log("IOMeter --threads=N --filesize=Y (MB) --buffersize=Z (bytes)");
		IOMeter meter = null;
		int threads = DEFAULT_THREADS;
		long filesize = DEFAULT_FILESIZE;
		int buffersize = DEFAULT_BUFSIZE;
		
		if (args.length > 0) {
			threads = parseArgument("--threads", args, threads);
			buffersize = parseArgument("--buffersize", args, buffersize);
			filesize = (parseArgument("--filesize", args, filesize)*1024)*1024;
		}
		
		meter = new IOMeter(threads, filesize,
					buffersize);
		
		meter.doBenchmark();

	}

	private static <T> T parseArgument(String argumentName, String[] args, T defaultValue) {
		
		for (int i = 0; i < args.length; i ++) {
			if (args[i].startsWith(argumentName) ) {
				String[] nameValue = args[i].split("=");
				String value = nameValue[1];
				if (defaultValue.getClass().isAssignableFrom( java.lang.Integer.class )) {
					return (T) Integer.valueOf(value);
				} else if (defaultValue.getClass().isAssignableFrom( java.lang.Long.class )) {
					return (T) Long.valueOf(value);
				}
				
			}
		}
		
		return defaultValue;
	}

	private void doBenchmark() {
		CyclicBarrier fileCreationBarrier = new CyclicBarrier(threads); // threads
																		// all
																		// create
																		// files
																		// on
																		// this
																		// barrier
		CyclicBarrier ioTestBarrier = new CyclicBarrier(threads + 1); // threads
																		// all
																		// do
																		// tests
																		// on
																		// this
																		// barrier
		for (int i = 0; i < threads; i++) {
			pool.execute(new IOThread(fileCreationBarrier, ioTestBarrier));
		}

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				this.setName("Statistics");
				printStats(statsMap);
				printIOPs();
			}
		});
		
		
		try {
			//log("waiting for IO Tests to complete");
			ioTestBarrier.await();
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (BrokenBarrierException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		log("Test complete, threads exited.");
		log("statistics map :" + statsMap);
		
		pool.shutdownNow();
	}

	private void printIOPs() {
		int min = Integer.MAX_VALUE;
		int max = Integer.MIN_VALUE;
		int count = 0;
		int sum = 0;
		//log("\n------\n"+iops+"\n------");
		for (String statType : iops.keySet()) {
			Map<Integer, AtomicInteger> stats = iops.get(statType);
			for (int second : stats.keySet()) {
				int value = stats.get(second).intValue();
				if (value != 0) {
					min = Math.min(min, value);
					max = Math.max(max, value);
					count ++;
					sum += value;
				}
			}
			double avg = sum*1.0/count;
			log( " * IOPS ("+statType +"): min : "+min+", max: "+max+", average: "+avg);
		}
	}

	private void printStats(HashMap<String, Calculator> smaps) {

		HashMap<String, Double> totals = new HashMap<String, Double>();
		// totals
		for (String operation : smaps.keySet()) {
			String[] split = operation.split("-");
			if (totals.containsKey(split[0])) {
				totals.put(split[0],
						totals.get(split[0]) + smaps.get(operation).mbPerSec);
			} else {
				totals.put(split[0], smaps.get(operation).mbPerSec);
			}
		}
		log(" ");
		for (String total : totals.keySet()) {
			log(" * " + total + " " + totals.get(total) + " MB/sec");
		}
	}

	class IOThread implements Runnable {

		CyclicBarrier fileBarrier;
		CyclicBarrier ioTestBarrier;
		String fileName;

		public IOThread(CyclicBarrier fileBarrier, CyclicBarrier ioTestBarrier) {
			this.fileBarrier = fileBarrier;
			this.ioTestBarrier = ioTestBarrier;
		}

		@Override
		public void run() {
			try {
				createFileForTest();
				fileBarrier.await();
			} catch (InterruptedException e) {
				log(e.getMessage());
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				log(e.getMessage());
				e.printStackTrace();
			}
			
			
			try {
				doIOBenchmark();
				ioTestBarrier.await();
			} catch (InterruptedException e) {
				log(e.getMessage());
				e.printStackTrace();
			} catch (BrokenBarrierException e) {
				log(e.getMessage());
				e.printStackTrace();
			}

		}

		private void doIOBenchmark() {
			File toTest = new File(fileName);
			if (!toTest.exists() || !toTest.canWrite()) {
				log("File not found or cannot write to it.");
			} else {
				toTest.deleteOnExit(); // cleanup hook
				log("Starting sequential read test");
				doSequentialReadTest(toTest);
				log("Starting random read test");
				doRandomReadTest(toTest);
				log("worker done.");
			}
		}

		private void doRandomReadTest(File toTest) {
			RandomAccessFile raf = null;
			long start = System.currentTimeMillis();
			try {
				byte[] tempBuff = new byte[bufferSize];
				raf = new RandomAccessFile(toTest,"r");
				
				for (int i = 0; i < seeksToTry; i++) {
					long seekPoint = ((long) ( random.nextDouble() * availablePoints) * bufferSize);
					raf.seek(seekPoint);
					raf.read(tempBuff);
					addIOP("Read Random");
				}
			} catch (IOException e) {
				log("FAILED TO DO RANDOM TEST");
				e.printStackTrace();
			} finally {
				if (raf!=null) {
					try {
						raf.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			
			long end = System.currentTimeMillis();
			
			calcMBsec("READRANDOM", start, end, 1000*bufferSize);

		}

		private void doSequentialReadTest(File toTest) {
			BufferedInputStream str = null;
			long start = System.currentTimeMillis();

			try {
				str = new BufferedInputStream(new FileInputStream(toTest),
						bufferSize);
				byte[] data = new byte[bufferSize];

				int r = 0;
				while ((r = str.read(data)) != -1) {
					// nothing, just read
					addIOP("Read Sequential");
				}

			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

			long end = System.currentTimeMillis();

			calcMBsec("READ", start, end, fileSize);
		}

		private void createFileForTest() {
			long start = System.currentTimeMillis();
			BufferedOutputStream str = null;
			try {
				fileName = "bench" + Thread.currentThread().getId() + ".dat";
				str = new BufferedOutputStream(new FileOutputStream(new File(
						fileName)), bufferSize);

				long fileSizeNow = 0;//must be a long!
				log("Creating a file of size " + (fileSize/1024/1024)
						+ " MB using a buffer of " + bufferSize + " bytes");
				byte[] temp = Arrays.copyOfRange(dataBlock,0,dataBlock.length);
				while (fileSizeNow < fileSize) {
					str.write(temp);
					addIOP("Write");
					fileSizeNow += temp.length;
				}
				str.flush();
			} catch (IOException ioe) {
				log("Failed to create file, error was " + ioe.getMessage());
			} finally {
				if (str != null) {
					try {
						str.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
			long end = System.currentTimeMillis();

			calcMBsec("WRITE", start, end, fileSize);
		}

	}

	class Calculator {
		private long start;
		private long end;
		private long seconds;
		private long bytes;
		private double mbPerSec;
		private String operation;

		public Calculator(String op, long st, long ed, long bt) {
			start = st;
			end = ed;
			bytes = bt;
			operation = op;
			seconds = (end - start) / 1000;
			mbPerSec = (bytes * 1.0 / 1024 / 1024) / seconds;
		}

		public String toString() {
			return "[Test " + operation + " duration : " + seconds + " sec, "
					+ mbPerSec + " MB/sec]";
		}
	}

	private HashMap<String, Calculator> statsMap = new HashMap<String, Calculator>();

	private void calcMBsec(String operation, long start, long end, long bytes) {
		statsMap.put(operation + "-" + Thread.currentThread().getId(),
				new Calculator(operation, start, end, bytes));
	}
	
	
	
	Map<String, Map<Integer, AtomicInteger>> iops = new HashMap<String, Map<Integer,AtomicInteger>>(180);
	
	public void addIOP(String ofWhichType) {
		int second = (int) (System.currentTimeMillis() / 1000);
		Map<Integer, AtomicInteger> stats = iops.get(ofWhichType);
		synchronized (iops) {
			if (!iops.containsKey(ofWhichType)) {
				stats = new HashMap<Integer,AtomicInteger>(120);
				iops.put(ofWhichType, stats);
			} else if (stats == null) {
				stats = iops.get(ofWhichType);
			}
		}
		
		boolean newinsert = false;
		
		synchronized (stats) {
			if (!stats.containsKey(second)) {
				stats.put(second, new AtomicInteger(1));
				newinsert = true;
			} 
		}
		if (!newinsert) {
			stats.get(second).incrementAndGet();
		}
		
	}
	
	
	
}
