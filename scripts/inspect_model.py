import json
import tensorflow as tf
interp = tf.lite.Interpreter('app/src/main/assets/aasist_int8.tflite')
interp.allocate_tensors()
print('IN:', json.dumps([{k:str(v) for k,v in i.items() if k != 'name'} for i in interp.get_input_details()]))
print('OUT:', json.dumps([{k:str(v) for k,v in i.items() if k != 'name'} for i in interp.get_output_details()]))
