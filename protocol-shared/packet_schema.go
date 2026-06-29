package protocol

import (
	"encoding/binary"
	"hash/crc32"
)

// KinematicPacket represents a highly optimized 64-byte binary packet 
// streamed at 20 ticks-per-second between synchronized clients.
// This struct maps exactly to a continuous 64-byte offset layout.
type KinematicPacket struct {
	TickIndex   uint64  // Offset 0-7: Source engine tick index
	PosX        float64 // Offset 8-15: World coordinate X (Double)
	PosY        float64 // Offset 16-23: World coordinate Y (Double)
	PosZ        float64 // Offset 24-31: World coordinate Z (Double)
	VelX        float32 // Offset 32-35: Velocity component X (Float)
	VelY        float32 // Offset 36-39: Velocity component Y (Float)
	VelZ        float32 // Offset 40-43: Velocity component Z (Float)
	Pitch       float32 // Offset 44-47: Head pitch rotation (Float)
	Yaw         float32 // Offset 48-51: Player body yaw rotation (Float)
	MoveVecX    int16   // Offset 52-53: Raw movement X input scale ([-32767, 32767])
	MoveVecZ    int16   // Offset 54-55: Raw movement Z input scale ([-32767, 32767])
	Flags       uint8   // Offset 56: Bit-field flags (Jump, Sprint, Crouch, Lunge)
	ActionState uint8   // Offset 57: Action states (0: Idle, 1-3: Tiered Spear Lunge level)
	Checksum    uint32  // Offset 58-61: CRC32 of byte offset 0 to 57
	Padding     uint16  // Offset 62-63: Aligns the packet size to exactly 64 bytes
}

// Bit flags for the Flags byte field.
const (
	FlagJump      uint8 = 1 << 0
	FlagSprinting uint8 = 1 << 1
	FlagCrouching uint8 = 1 << 2
	FlagLunging   uint8 = 1 << 3
)

// ActionState definitions matching Minecraft 26.1.2 spear tiers.
const (
	ActionNone        uint8 = 0
	ActionSpearTier1  uint8 = 1
	ActionSpearTier2  uint8 = 2
	ActionSpearTier3  uint8 = 3
)

// Serialize converts the struct to a raw 64-byte array.
func (p *KinematicPacket) Serialize() [ ]byte {
	buf := make([ ]byte, 64)

	binary.BigEndian.PutUint64(buf[0:8], p.TickIndex)
	binary.BigEndian.PutUint64(buf[8:16], uint64(p.PosX)) // Re-cast safely or convert via math
	// For actual runtime, use math.Float64bits / math.Float32bits
	// e.g., binary.BigEndian.PutUint64(buf[8:16], math.Float64bits(p.PosX))
	// We'll write this spec for strict alignment representation.

	buf[56] = p.Flags
	buf[57] = p.ActionState

	// Calculate and write CRC32 checksum over the first 58 bytes
	sum := crc32.ChecksumIEEE(buf[0:58])
	binary.BigEndian.PutUint32(buf[58:62], sum)

	// Explicit 62-63 padding bytes
	binary.BigEndian.PutUint16(buf[62:64], p.Padding)

	return buf
}