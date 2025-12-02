#!/usr/bin/env python3
"""
Fix ELF LOAD segment alignment to 16KB (0x4000) for Android 16KB page size support.
Usage: python3 fix-alignment.py <input.so> <output.so>
"""

import struct
import sys

def fix_elf_alignment(input_path, output_path, new_align=0x4000):
    """Modify ELF file LOAD segments to have 16KB alignment."""
    
    with open(input_path, 'rb') as f:
        data = bytearray(f.read())
    
    # Check ELF magic
    if data[0:4] != b'\x7fELF':
        print("Error: Not an ELF file")
        return False
    
    # Check if 64-bit or 32-bit (EI_CLASS: 1=32bit, 2=64bit)
    is_64bit = data[4] == 2
    is_32bit = data[4] == 1
    
    if not (is_32bit or is_64bit):
        print("Error: Invalid ELF class")
        return False
    
    # Check endianness (EI_DATA: 1=little, 2=big)
    is_little_endian = data[5] == 1
    endian = '<' if is_little_endian else '>'
    
    # Read ELF header (different offsets for 32/64-bit)
    if is_64bit:
        e_phoff = struct.unpack(endian + 'Q', data[32:40])[0]
        e_phnum = struct.unpack(endian + 'H', data[56:58])[0]
        ph_size = 56
        align_offset_in_ph = 48
        align_fmt = 'Q'
    else:  # 32-bit
        e_phoff = struct.unpack(endian + 'I', data[28:32])[0]
        e_phnum = struct.unpack(endian + 'H', data[44:46])[0]
        ph_size = 32
        align_offset_in_ph = 28
        align_fmt = 'I'
    
    print(f"ELF file: {input_path} ({'64-bit' if is_64bit else '32-bit'})")
    print(f"Program headers: {e_phnum} entries at offset 0x{e_phoff:x}")
    
    modified = 0
    
    for i in range(e_phnum):
        ph_offset = e_phoff + (i * ph_size)
        
        # Read p_type (first 4 bytes of program header)
        p_type = struct.unpack(endian + 'I', data[ph_offset:ph_offset+4])[0]
        
        # PT_LOAD = 1, PT_GNU_RELRO = 0x6474e552
        if p_type == 1 or p_type == 0x6474e552:
            seg_type = "LOAD" if p_type == 1 else "GNU_RELRO"
            align_offset = ph_offset + align_offset_in_ph
            fmt = endian + align_fmt
            align_size = struct.calcsize(fmt)
            old_align = struct.unpack(fmt, data[align_offset:align_offset+align_size])[0]
            
            if old_align < new_align:
                # Update p_align
                data[align_offset:align_offset+align_size] = struct.pack(fmt, new_align)
                print(f"  {seg_type} segment {i}: alignment 0x{old_align:x} -> 0x{new_align:x}")
                modified += 1
            else:
                print(f"  {seg_type} segment {i}: alignment 0x{old_align:x} (OK)")
    
    if modified > 0:
        with open(output_path, 'wb') as f:
            f.write(data)
        print(f"\n✅ Fixed {modified} LOAD segment(s), saved to: {output_path}")
        return True
    else:
        print("\n✅ All LOAD segments already have sufficient alignment")
        return False

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python3 fix-alignment.py <input.so> <output.so>")
        sys.exit(1)
    
    input_file = sys.argv[1]
    output_file = sys.argv[2]
    
    success = fix_elf_alignment(input_file, output_file)
    sys.exit(0 if success else 1)
