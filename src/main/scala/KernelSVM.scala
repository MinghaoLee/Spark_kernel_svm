/* 
 * Kernel SVM: the class for kernelized SVM on Spark
 * Using SGD
 * Usage example: 
    //data = some rdd of LabeledPoint
    //setup amodel by regietering training data, specifying lambda, 
    //specifying kernel and kernel parameters
    val model = new KernelSVM(data_train, 1.0, "rbf", 1.0)
    //train the model by specifying # of iterations and packing size
    model.train(1000,10)
 */
import org.apache.spark.rdd._
import Array._

import org.apache.spark.mllib.regression.LabeledPoint
import edu.berkeley.cs.amplab.spark.indexedrdd.IndexedRDD


class KernelSVM(training_data:RDD[LabeledPoint], lambda_s: Double, kernel : String = "rbf", gamma: Double = 1D, checkpoint_dir: String = "../checkpoint") extends java.io.Serializable{
    var lambda = lambda_s
    var kernel_func = new RbfKernelFunc(gamma)
    var model = training_data.map(x => (x, 0D))
    var data = training_data
    var s = 1D

    data.sparkContext.setCheckpointDir(checkpoint_dir)

    /** Packing algorithm **/
    def train(num_iter: Long, pack_size: Int = 1) {
        /** Initialization */
        var working_data = IndexedRDD(data.zipWithUniqueId().map{case (k,v) => (v,(k, 0D))})
        var norm = 0D
        var alpha = 0D
        var t = 1
        var i = 0
        var j = 0
        var num_of_updates = 0

        val pair_idx = data.sparkContext.parallelize(range(0, pack_size).flatMap(x => (range(x, pack_size).map(y => (x,y)))))


        /** Training the model with pack updating */
        while (t <= num_iter) {

            var sample = working_data.takeSample(true, pack_size)
            var yp = sample.map(x => (working_data.map{case (k,v) => (v._1.label * v._2 * kernel_func.evaluate(v._1.features, x._2._1.features))}.reduce((a, b) => a + b)))
            var y = sample.map(x => x._2._1.label)
            var local_set = Map[Long, (LabeledPoint, Double)]()
            //var inner_prod = Map[(Int, Int), Double]()

            /** Compute kernel inner product pairs*/
            /*
            for (i <- 0 until pack_size) {
                for (j <- i until pack_size) {
                    inner_prod = inner_prod + ((i, j) -> kernel_func.evaluate(sample(i)._2._1.features, sample(j)._2._1.features))
                }
            }
            */
            var inner_prod = pair_idx.map(x => (x, kernel_func.evaluate(sample(x._1)._2._1.features, sample(x._2)._2._1.features))).collectAsMap()

            for (i <- 0 until pack_size) {
                t = t+1
                s = (1 - 1D/(t))*s
                for (j <- (i+1) until (pack_size)) {
                    yp(j) = (1 - 1D/(t))*yp(j)
                }
                if (y(i) * yp(i) < 1) {
                    norm = norm + (2*y(i)) / (lambda * t) * yp(i) + math.pow((y(i)/(lambda*t)), 2)*inner_prod((i,i))
                    alpha = sample(i)._2._2
                    local_set = local_set + (sample(i)._1 -> (sample(i)._2._1, alpha + (1/(lambda*t*s))))

                    for (j <- (i+1) to (pack_size-1)) {
                        yp(j) = yp(j) + y(j)/(lambda*t) * inner_prod((i,j))
                    }

                    if (norm > (1/lambda)) {
                        s = s * (1/math.sqrt(lambda*norm))
                        norm = (1/lambda)
                        for (j <- (i+1) to (pack_size-1)) {
                            yp(j) = yp(j) /math.sqrt(lambda*norm)
                        }
                    }

                }
            }
            //batch update model
            working_data = working_data.multiput(local_set).cache()
            num_of_updates = num_of_updates + 1
            //checkpoint
            if (num_of_updates % 100 == 0 ) {
                working_data.checkpoint()
            }
        }
        //keep the effective part of the model
        model = working_data.map{case (k, v) => (v._1, v._2)}.filter{case (k,v) => (v > 0)}

    }

    def getNumSupportVectors(): Long = {
        model.count()
    }

    def predict (data: LabeledPoint): Double = {
        s * (model.map{case (k,v) => v * k.label * kernel_func.evaluate(data.features, k.features)}.reduce((a, b) => a + b))

    }
    
    def getAccuracy(data: Array[LabeledPoint]): Double = {
        val N_c = data.map(x => (predict(x) * x.label) ).count(x => x>0)
        val N = data.count(x => true)
        (N_c.toDouble / N)

    }

    def registerData(new_training_data:RDD[LabeledPoint]) {
        data = new_training_data
    }

    def setLambda(new_lambda: Double) {
        lambda = new_lambda
    }

}