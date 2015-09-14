package net.kajos.median;

import android.hardware.*;
import android.util.Log;
import android.util.SparseArray;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import static de.robv.android.xposed.XposedHelpers.findClass;

import android.hardware.Sensor;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;


public class MedianFilter implements IXposedHookLoadPackage {
	public XSharedPreferences pref;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
    	pref = new XSharedPreferences(MedianFilter.class.getPackage().getName(), "pref_median"); // load the preferences using Xposed (necessary to be accessible from inside the hook, SharedPreferences() won't work)
    	pref.makeWorldReadable();

        try {
            final Class<?> sensorEQ = findClass(
                    "android.hardware.SystemSensorManager$SensorEventQueue",
                    lpparam.classLoader);

            XposedBridge.hookAllMethods(sensorEQ, "dispatchSensorEvent", new
                    XC_MethodHook() {
            			int filter_size = 10;
            			float filter_min_change = 0.0f; // TODO: should use a different threshold for each dimension

		            	// Init the variables
                        float medianValues[][] = new float[3][filter_size]; // stores the last sensor's values in each dimension (3D so 3 dimensions)
                        float tmpArray[] = new float[medianValues[0].length]; // used to temporarily copy medianValues to compute the median

                        private void changeSensorEvent(float[] values) {
                        	// Get preferences
                        	pref.makeWorldReadable(); // try to make the preferences world readable (because here we are inside the hook, we are not in our app anymore, so normally we do not have the rights to access the preferences of our app)
                        	pref.reload(); // reload the preferences to get the latest value (ie, if the user changed the values without rebooting the phone)
                        	int filter_size_new = Integer.parseInt(pref.getString("filter_size", "10")); // number of sample values to keep to compute the median
                        	float filter_min_change_new = Float.parseFloat(pref.getString("filter_min_change", "0.0")); // minimum value change threshold in sensor's value to aknowledge the new value (otherwise don't change the value, but we still register the new value in the median array)

                        	// Copy new value of min change threshold
                        	filter_min_change = filter_min_change_new;
                        	// Resize the medianValues array and copy the new filter size
                        	if (filter_size_new != filter_size) {
                        		float cpyArray[][] = new float[3][filter_size];
                        		for (int k = 0; k < 3; k++) {
                        			for (int i = 0; i < filter_size; i++) {
                        				cpyArray[k][i] = medianValues[k][i];
                        			}
                        		}
                        		medianValues = new float[3][filter_size_new];
                        		for (int k = 0; k < 3; k++) {
                        			for (int i = 0; i < filter_size; i++) {
                        				medianValues[k][i] = cpyArray[k][i];
                        			}
                        		}
                        		filter_size = filter_size_new;
                        	}

                        	Log.d("MedianFilter", "MedianFilter variables: filter_size: "+Integer.toString(filter_size)+" filter_min_change:"+Float.toString(filter_min_change));

                            // Process the gyroscope's values (3D so 3 values)
                            for (int k = 0; k < 3; k++) { // for each of the 3 dimensions of the gyro
                        		// -- Updating the medianValues array (which stores the last known values to be able to compute the median)
                                for (int i = tmpArray.length - 1; i > 0; i--) { // shift the values in the medianValues array (we forget the oldest value at the end of the array)
                                    medianValues[k][i] = medianValues[k][i - 1];
                                }

                                // Add new value (insert at index 0)
                                medianValues[k][0] = values[k];

                                // -- Compute the median and replace the current gyroscope's value
                                // Copy the values to a temporary array
                                for (int f = 0; f < tmpArray.length; f++) {
                                    tmpArray[f] = medianValues[k][f];
                                }

                                // Sort the values
                                Arrays.sort(tmpArray);

                                // Pick the median value
                                float median = tmpArray[tmpArray.length/2];

                                
                                if (filter_min_change <= 0.0f || // either filter min change threshold is disabled (value == 0)
                                		Math.abs(median - medianValues[k][1]) >= filter_min_change) { // or it is enabled (value > 0) and then we check if the current median difference with the previous sensor's value is above the minimum change threshold
	                                // Set median in gyroscope
	                                values[k] = median;
	                                Log.d("MedianFilter", "MedianFilter median: "+Float.toString(median)+" previous_val:"+Float.toString(medianValues[k][1]));
                                } else {
                                	Log.d("MedianFilter", "MedianFilter NOPE median: "+Float.toString(median)+" current_val:"+Float.toString(medianValues[k][0]));
                                	values[k] = medianValues[k][1];
                                	medianValues[k][0] = medianValues[k][1];
                                }
                            }
                        }

                        @SuppressWarnings("unchecked")
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws
                                Throwable {
                            Field field = param.thisObject.getClass().getEnclosingClass().getDeclaredField("sHandleToSensor");
                            field.setAccessible(true);
                            int handle = (Integer) param.args[0];
                            Sensor ss = ((SparseArray<Sensor>) field.get(0)).get(handle);
                            if(ss.getType() == Sensor.TYPE_GYROSCOPE){
                                changeSensorEvent((float[]) param.args[1]);
                            }
                        }
                    });

            XposedBridge.log("Installed sensorevent patch in: " + lpparam.packageName);

        } catch (Throwable t) {
            // Do nothing
        }
    }
}