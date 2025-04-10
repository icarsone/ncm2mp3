import os
import json
import base64
import struct
import logging
import binascii
import traceback
from glob import glob
from tqdm.auto import tqdm
from textwrap import dedent
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.backends import default_backend

# 配置日志记录
logging.basicConfig(level=logging.DEBUG)
logger = logging.getLogger(__name__)

def create_result_dict(success, output_path=None, format=None, error=None, meta_data=None):
    """创建一个简单的结果字典"""
    return {
        "success": bool(success),  # 确保是布尔值
        "output_path": str(output_path) if output_path else None,
        "format": str(format) if format else None,
        "error": str(error) if error else None,
        "meta_data": dict(meta_data) if meta_data else None
    }

def convert_file(input_path, output_folder, output_filename=None):
    try:
        logger.info(f"Starting conversion of file: {input_path}")
        logger.info(f"Output folder: {output_folder}")
        logger.info(f"Output filename: {output_filename}")
        
        if not os.path.exists(input_path):
            logger.error(f"Input file does not exist: {input_path}")
            return create_result_dict(False, error="Input file does not exist")
            
        if not os.path.exists(output_folder):
            logger.info(f"Creating output folder: {output_folder}")
            try:
                os.makedirs(output_folder)
            except Exception as e:
                logger.error(f"Failed to create output folder: {str(e)}")
                return create_result_dict(False, error=f"Failed to create output folder: {str(e)}")
            
        filename = os.path.basename(input_path)
        if not filename.endswith('.ncm'):
            logger.error(f"Not a NCM file: {filename}")
            return create_result_dict(False, error="Not a NCM file")
            
        # 使用自定义文件名或默认文件名（去掉.ncm后缀）
        base_filename = output_filename if output_filename else filename[:-4]
        logger.info(f"Using base filename: {base_filename}")
        
        core_key = binascii.a2b_hex('687A4852416D736F356B496E62617857')
        meta_key = binascii.a2b_hex('2331346C6A6B5F215C5D2630553C2728')
        unpad = lambda s: s[0:-(s[-1] if isinstance(s[-1], int) else ord(s[-1]))]
        
        with open(input_path, 'rb') as f:
            logger.debug("Reading file header...")
            header = f.read(8)
            if binascii.b2a_hex(header) != b'4354454e4644414d':
                logger.error("Invalid NCM file header")
                return create_result_dict(False, error="Invalid NCM file header")
            
            logger.debug("Reading key data...")
            f.seek(2, 1)
            key_length = struct.unpack('<I', bytes(f.read(4)))[0]
            key_data = f.read(key_length)
            key_data_array = bytearray(key_data)
            
            for i in range(len(key_data_array)):
                key_data_array[i] ^= 0x64
                
            key_data = bytes(key_data_array)
            cryptor = Cipher(algorithms.AES(core_key), modes.ECB(), backend=default_backend()).decryptor()
            key_data = unpad(cryptor.update(key_data) + cryptor.finalize())[17:]
            key_length = len(key_data)
            key_data = bytearray(key_data)
            key_box = bytearray(range(256))
            
            logger.debug("Generating key box...")
            c = 0
            last_byte = 0
            key_offset = 0
            
            for i in range(256):
                swap = key_box[i]
                c = (swap + last_byte + key_data[key_offset]) & 0xff
                key_offset += 1
                if key_offset >= key_length:
                    key_offset = 0
                key_box[i] = key_box[c]
                key_box[c] = swap
                last_byte = c
            
            logger.debug("Reading metadata...")
            meta_length = struct.unpack('<I', bytes(f.read(4)))[0]
            meta_data = f.read(meta_length)
            meta_data_array = bytearray(meta_data)
            
            for i in range(len(meta_data_array)):
                meta_data_array[i] ^= 0x63
                
            meta_data = bytes(meta_data_array)
            meta_data = base64.b64decode(meta_data[22:])
            cryptor = Cipher(algorithms.AES(meta_key), modes.ECB(), backend=default_backend()).decryptor()
            meta_data = unpad(cryptor.update(meta_data) + cryptor.finalize()).decode('utf-8')[6:]
            meta_data = json.loads(meta_data)
            logger.info(f"Metadata: {meta_data}")
            
            crc32 = struct.unpack('<I', bytes(f.read(4)))[0]
            f.seek(5, 1)
            image_size = struct.unpack('<I', bytes(f.read(4)))[0]
            image_data = f.read(image_size)
            
            output_path = os.path.join(output_folder, f'{base_filename}.{meta_data["format"]}')
            logger.info(f"Output path: {output_path}")
            
            logger.debug("Converting file content...")
            with open(output_path, 'wb') as m:
                while True:
                    chunk = bytearray(f.read(0x8000))
                    if not chunk:
                        break
                    for i in range(1, len(chunk) + 1):
                        j = i & 0xff
                        chunk[i - 1] ^= key_box[(key_box[j] + key_box[(key_box[j] + j) & 0xff]) & 0xff]
                    m.write(chunk)
            
            logger.info("Conversion completed successfully")
            return create_result_dict(
                success=True,
                output_path=output_path,
                format=meta_data["format"],
                meta_data=meta_data
            )
            
    except Exception as e:
        logger.error(f"Error during conversion: {str(e)}")
        logger.error(f"Traceback: {traceback.format_exc()}")
        return create_result_dict(
            success=False,
            error=f"Error during conversion: {str(e)}\nTraceback: {traceback.format_exc()}"
        )

def get_version():
    return "1.0.0" 