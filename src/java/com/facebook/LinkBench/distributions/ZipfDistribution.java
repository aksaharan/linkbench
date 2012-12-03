package com.facebook.LinkBench.distributions;

import java.util.ArrayList;
import java.util.Properties;
import java.util.Random;

import org.apache.commons.math3.util.FastMath;
import org.apache.log4j.Logger;

import com.facebook.LinkBench.Config;
import com.facebook.LinkBench.ConfigUtil;

public class ZipfDistribution implements ProbabilityDistribution {
  private final Logger logger = Logger.getLogger(ConfigUtil.LINKBENCH_LOGGER); 
  
  private long min = 0;
  private long max = 1;
  private double shape = 0.0;
  
  /** The total number of items in the world */
  private double scale;
  
  // precomputed values
  private double alpha = 0.0;
  private double eta = 0.0;
  private double zetan = 0.0;
  private double point5theta = 0.0;
  
  
  @Override
  public void init(long min, long max, Properties props, String keyPrefix) {
    if (max <= min) {
      throw new IllegalArgumentException("max = " + max + " <= min = " + min +
          ": probability distribution cannot have zero or negative domain");
    }
    if (max - min > Integer.MAX_VALUE) {
      throw new IllegalArgumentException("Zipf distribution does not currently " +
      		"support distributions with id range > " + Integer.MAX_VALUE +
      		": range [" + min + ", " + max + ") is too large.");
    }
    
    this.min = min;
    this.max = max;
    String shapeS = props != null ? ConfigUtil.getPropertyRequired(props,
                                  keyPrefix + "shape") : null;
    if (shapeS == null ) {
      throw new IllegalArgumentException("ZipfDistribution must be provided " +
      		keyPrefix + "shape parameter");
    }
    shape = Double.valueOf(shapeS);
    if (shape <= 0.0) {
      throw new IllegalArgumentException("Zipf shape parameter " + shape + 
          " is not positive");
          
    }
    
    if (props != null && props.containsKey(keyPrefix + Config.PROB_MEAN)) {
      scale = (max - min) * ConfigUtil.getDouble(props, 
                                  keyPrefix + Config.PROB_MEAN);
    } else {
      scale = 1.0;
    }
    
    // Precompute some values to speed up future method calls
    long n = max - min;
    alpha = 1 / (1 - shape);
    zetan = calcZetan(n);
    eta = (1 - FastMath.pow(2.0 / n, 1 - shape)) /
          (1 - Harmonic.generalizedHarmonic(2, shape) / zetan);
    point5theta = FastMath.pow(0.5, shape);
  }

  

  // For large n, calculating zetan takes a long time. This is a simple
  // but effective caching technique that speeds up startup a lot
  // when multiple instances of the distribution are initialized in
  // close succession.
  private static class CacheEntry {
    long n;
    double shape;
    double zetan;
  }
  
  /** Min value of n to cache */
  private static final long MIN_CACHE_VALUE = 1000;
  private static final int MAX_CACHE_ENTRIES = 1024;
  
  private static ArrayList<CacheEntry> zetanCache = 
                new ArrayList<CacheEntry>(MAX_CACHE_ENTRIES);
  
  private double calcZetan(long n) {
    if (n < MIN_CACHE_VALUE) {
      return uncachedCalcZetan(n);
    }
    
    synchronized(ZipfDistribution.class) {
      for (int i = 0; i < zetanCache.size(); i++) {
        CacheEntry ce = zetanCache.get(i);
        if (ce.n == n && ce.shape == shape) {
          return ce.zetan;
        }
      }
    }

    double calcZetan = uncachedCalcZetan(n);
    
    synchronized (ZipfDistribution.class) {
      CacheEntry ce = new CacheEntry();
      ce.zetan = calcZetan;
      ce.n = n;
      ce.shape = shape;
      if (zetanCache.size() >= MAX_CACHE_ENTRIES) {
        zetanCache.remove(0);
      }
      zetanCache.add(ce);
    }
    return calcZetan;
  }

  private double uncachedCalcZetan(long n) {
    double calcZetan;
    if (shape <= 1.0) {
      // use approximation
      calcZetan = Harmonic.generalizedHarmonicApprox(n, shape);
    } else {
      // Can't use approximation
      // If calculation will take more than 5 or so seconds, let user know
      // what is happening
      if (n > 20000000) {
        logger.info("Precalculating constants for Zipf distribution over "
                    + n + " items with shape = " + shape 
                    + ".  Please be patient, this can take a little time.");
      }
      
      calcZetan = Harmonic.generalizedHarmonic(n, shape);
    }
    return calcZetan;
  }

  @Override
  public double pdf(long id) {
    return scaledPDF(id, 1.0);
  }
  
  @Override
  public double expectedCount(long id) {
    return scaledPDF(id, scale);
  }
  
  private double scaledPDF(long id, double scale) {
    // Calculate this way to avoid losing precision by calculating very
    // small pdf number
    if (id < min || id >= max) return 0.0;
    return (scale / (double) FastMath.pow(id + 1 - min, shape))/ zetan;
  }
  
  @Override
  public double cdf(long id) {
    if (id < min) return 0.0;
    if (id >= max) return 1.0;
    double harm;
    if (shape <= 1.0) {
      harm = Harmonic.generalizedHarmonicApprox(id + 1 - min, shape);
    } else {
      harm = Harmonic.generalizedHarmonic(id + 1 - min, shape);
    }
    return harm / zetan;
  }

  /**
   * Algorithm from "Quickly Generating Billion-Record Synthetic Databases",
   * Gray et. al., 1994
   * 
   * Pick a value in range [min, max) according to zipf distribution,
   * with min being the most likely to be chosen
   */
  @Override
  public long choose(Random rng) {
    return quantile(rng.nextDouble());
  }

  /**
   * Quantile function
   * 
   * parts of formula are precomputed in init since they are expensive
   * to calculate and only depend on the distribution parameters
   */
  public long quantile(double p) {
    double uz = p * zetan;
    long n = max - min;
    if (uz < 1) return min;
    if (uz < 1 + point5theta) return min + 1;
    long offset = (long) (n * FastMath.pow(eta * p - eta + 1, alpha));
    if (offset >= n) return max - 1;
    return min + offset;
  }
}
