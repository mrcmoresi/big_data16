import java.util.Calendar
import java.sql.Timestamp
import java.io.File
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Dataset
import org.apache.spark.sql.expressions.Window
import org.apache.spark.sql.functions._
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.Encoder
import scala.util.{Try, Success, Failure}

object Sessionalization{ 
    
    //Defino las clases
    case class EventLog(timeStamp: Long, user_id: String, action_id: String, amount: Double)
    case class EventLogTs(timeStamp: java.sql.Timestamp, user_id: String, action_id: String, amount: Double)
    case class EventLogLag(timeStamp: Long, user_id: String, action_id: String, amount: Double, lagTimeStamp: BigInt)
    case class EventLogLagSesAcc(timeStamp: java.sql.Timestamp, user_id: String, action_id: String, amount: Double, lagTimeStamp: BigInt, belong: Int, session: Int)
    
    
    def main(args: Array[String]){
        val spark: SparkSession = SparkSession
        .builder()
            .master("local[*]")
            .appName("Sessionalization")
            .getOrCreate();
   
        import spark.implicits._
        val threshold = args(1).toInt
        
        val dir_in = readDirectory(spark, args(0))

        //Manejo el path de entrada y seteo path de salida
        var dir_out = ""
        val path = new File(args(0))
        if(path.isDirectory()){
            dir_out = path.getPath()
        }else{
            //si la entrada es un file, retorno el parent
            dir_out = path.getParent()
        }
        val eventLogBuilt = buildEventLog(spark, dir_in, dir_out)
        val eventLogLagBuilt = buildEventLogLag(spark, eventLogBuilt, dir_out)
        val eventLogLagAccDF = belong(spark, eventLogLagBuilt, threshold)
        querySessionalization(spark, eventLogLagAccDF, dir_out)
 
        spark.stop()
    }
    
    //defino funcion para determinar las sessiones
	def belong(spark: SparkSession, table: Dataset[EventLogLag], thresHoldMin: Long) : DataFrame = {
    	import spark.implicits._

	    val thresHoldInMs = thresHoldMin * 60 * 1000
	    var accumulator = 0
	    var id_prev = ""
	    var belong = 0
	 
	    val result = table.map(row => {
	        Try(if(row.timeStamp - row.lagTimeStamp > thresHoldInMs) {
	            belong = 1 // sobrepasa el umbral, entonces el user con timeStamp pertenece a una nueva sesion
	        } else {
	            belong = 0
	        } ).getOrElse(belong = 0)  

	        if(id_prev != row.user_id){
	            accumulator = 0
	        } else {
	            accumulator += belong
	        }
	        id_prev = row.user_id
		EventLogLagSesAcc(new Timestamp(row.timeStamp), row.user_id, row.action_id, row.amount, row.lagTimeStamp, belong, accumulator)
	    })

	    val resultDF = result.toDF()
			
	    return resultDF
	}
    
    //Cargo archivos del directorio
    def readDirectory(spark: SparkSession, path: String) : Dataset[Array[String]] = {
        import spark.implicits._
        
        val input_file = spark.read.text(path).as[String]
        val clean_file = input_file.map(line => line.split("\t"))
        
        return clean_file
    }
    
    //Creo Dataset de tipo EventLog a partir del archivo cargado
    def buildEventLog(spark: SparkSession, cleaned_DS: Dataset[Array[String]], dir_out : String) : Dataset[EventLog] = {
        import spark.implicits._

        val eventLog_DS = cleaned_DS.map(row => EventLog(Timestamp.valueOf(row(0)).getTime(), row(1), row(2), (Try(row(3).toDouble).getOrElse(0))))
        val eventLog_DSTS = cleaned_DS.map(row => EventLogTs(Timestamp.valueOf(row(0)), row(1), row(2), (Try(row(3).toDouble).getOrElse(0))))
        
        val eventLogTS_DF = eventLog_DSTS.toDF()
        eventLogTS_DF.write.save(s"${dir_out}/hits.parquet")
        return eventLog_DS
    }
    
    
    // Compute the previous lag and build the case class  EventLog_LagTS
    def buildEventLogLag(spark: SparkSession, eventLog_DS: Dataset[EventLog], dir_out: String) : Dataset[EventLogLag] = {
        import spark.implicits._
        val byUserId = Window.partitionBy('user_id).orderBy('timeStamp)
        val lagTimeStamp = lag('timeStamp, 1).over(byUserId)
        val cleanedloglag  = eventLog_DS.toDF.select('*, lagTimeStamp as 'lagTimeStamp).as[EventLogLag]
        
        return cleanedloglag
    }
    
    
    def querySessionalization(spark: SparkSession, table : DataFrame, dir_out: String) = {
        import spark.implicits._
        
        val play = "bd307a3ec329e10a2cff8fb87480823da114f8f4"
        val render = "12c6fc06c99a462375eeb3f43dfd832b08ca9e17"
        val checkout = "7b52009b64fd0a2a49e6d8a939753077792b0554"
        
        val newquery = table.groupBy($"user_id",$"session")
					    .agg(min($"timeStamp").as("BeginSession"),
                                            max($"timeStamp").as("EndSession"),sum(when(table("action_id") === play ,1).otherwise(0)).as("Play"),
                                            sum(when(table("action_id") === render ,1).otherwise(0)).as("Render"),
                                            sum(when(table("action_id") === checkout ,1).otherwise(0)).as("Checkout"), sum('amount).as("Total"))

        newquery.write.save(s"${dir_out}/result.parquet")
    }
}
